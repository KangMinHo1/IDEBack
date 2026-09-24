package com.myide.backend.service.apitest;

import com.myide.backend.dto.apitest.DiscoveredEndpointResponse;
import com.myide.backend.dto.project.ProjectListResponse;
import com.myide.backend.service.ProjectService;
import com.myide.backend.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiEndpointDiscoveryService {

    private static final String DEFAULT_BRANCH_NAME = "master";

    private static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
            ".git", "node_modules", ".next", "target", "build",
            "out", "dist", ".gradle", ".idea"
    );

    private static final Pattern CONTROLLER_PATTERN =
            Pattern.compile("@(?:RestController|Controller)\\b");

    private static final Pattern CLASS_PATTERN =
            Pattern.compile("\\bclass\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");

    private static final Pattern REQUEST_MAPPING_PATTERN =
            Pattern.compile("@RequestMapping\\s*(?:\\((.*?)\\))?", Pattern.DOTALL);

    private static final Pattern METHOD_MAPPING_PATTERN =
            Pattern.compile(
                    "@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping|RequestMapping)"
                            + "\\s*(?:\\((.*?)\\))?",
                    Pattern.DOTALL
            );

    private static final Pattern REQUEST_METHOD_PATTERN =
            Pattern.compile("RequestMethod\\.(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)");

    private final ProjectService projectService;
    private final WorkspaceService workspaceService;

    public List<DiscoveredEndpointResponse> findWorkspaceEndpoints(
            String workspaceId,
            String branchName
    ) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return List.of();
        }

        String branch = branchName == null || branchName.isBlank()
                ? DEFAULT_BRANCH_NAME
                : workspaceService.normalizeBranchName(branchName);

        List<ProjectListResponse> projects =
                projectService.getProjectsByWorkspace(workspaceId);

        // 작업 폴더가 하나도 없으면 무조건 빈 목록.
        // WAIVS 자체 Controller를 fallback으로 사용하지 않는다.
        if (projects == null || projects.isEmpty()) {
            return List.of();
        }

        List<DiscoveredEndpointResponse> result = new ArrayList<>();

        for (ProjectListResponse project : projects) {
            if (project == null
                    || project.getName() == null
                    || project.getName().isBlank()) {
                continue;
            }

            String projectName = project.getName();

            Path projectRoot;
            try {
                projectRoot = workspaceService.getProjectPath(
                        workspaceId,
                        projectName,
                        branch
                ).normalize();
            } catch (Exception e) {
                log.warn("[API 탐색] 프로젝트 경로 확인 실패: {}", projectName, e);
                continue;
            }

            // DB Project가 있어도 실제 작업 폴더가 없으면 건너뜀.
            if (!Files.exists(projectRoot) || !Files.isDirectory(projectRoot)) {
                continue;
            }

            scanProject(projectRoot, projectName, result);
        }

        return result.stream()
                .sorted(
                        Comparator
                                .comparing(
                                        DiscoveredEndpointResponse::getProjectName,
                                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                                )
                                .thenComparing(
                                        DiscoveredEndpointResponse::getPath,
                                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                                )
                                .thenComparing(
                                        DiscoveredEndpointResponse::getMethod,
                                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                                )
                )
                .toList();
    }

    private void scanProject(
            Path projectRoot,
            String projectName,
            List<DiscoveredEndpointResponse> result
    ) {
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(path ->
                            path.getFileName().toString()
                                    .toLowerCase(Locale.ROOT)
                                    .endsWith(".java")
                    )
                    .filter(path -> !containsIgnoredDirectory(projectRoot, path))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(
                                    path,
                                    StandardCharsets.UTF_8
                            );
                            parseControllerFile(
                                    projectRoot,
                                    path,
                                    projectName,
                                    source,
                                    result
                            );
                        } catch (Exception e) {
                            log.warn("[API 탐색] Java 파일 분석 실패: {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("[API 탐색] 프로젝트 탐색 실패: {}", projectRoot, e);
        }
    }

    private void parseControllerFile(
            Path projectRoot,
            Path file,
            String projectName,
            String rawSource,
            List<DiscoveredEndpointResponse> result
    ) {
        String source = stripComments(rawSource);

        if (!CONTROLLER_PATTERN.matcher(source).find()) {
            return;
        }

        Matcher classMatcher = CLASS_PATTERN.matcher(source);
        if (!classMatcher.find()) {
            return;
        }

        String controllerName = classMatcher.group(1);

        List<String> basePaths =
                extractClassRequestPaths(
                        source.substring(0, classMatcher.start())
                );

        if (basePaths.isEmpty()) {
            basePaths = List.of("");
        }

        String classBody = source.substring(classMatcher.end());
        Matcher mappingMatcher = METHOD_MAPPING_PATTERN.matcher(classBody);

        while (mappingMatcher.find()) {
            String annotation = mappingMatcher.group(1);
            String args = mappingMatcher.group(2);

            List<String> methods = extractHttpMethods(annotation, args);
            if (methods.isEmpty()) {
                continue;
            }

            List<String> methodPaths = extractMappingPaths(args);
            if (methodPaths.isEmpty()) {
                methodPaths = List.of("");
            }

            String relativeFilePath =
                    projectRoot.relativize(file)
                            .toString()
                            .replace("\\", "/");

            for (String basePath : basePaths) {
                for (String methodPath : methodPaths) {
                    String fullPath = combinePaths(basePath, methodPath);

                    for (String method : methods) {
                        result.add(
                                DiscoveredEndpointResponse.builder()
                                        .id(
                                                projectName + ":"
                                                        + method + ":"
                                                        + fullPath + ":"
                                                        + relativeFilePath
                                        )
                                        .method(method)
                                        .path(fullPath)
                                        .projectName(projectName)
                                        .filePath(relativeFilePath)
                                        .controller(controllerName)
                                        .handler(null)
                                        .build()
                        );
                    }
                }
            }
        }
    }

    private List<String> extractClassRequestPaths(String classHeader) {
        Matcher matcher = REQUEST_MAPPING_PATTERN.matcher(classHeader);
        List<String> latest = List.of();

        while (matcher.find()) {
            List<String> paths = extractMappingPaths(matcher.group(1));
            latest = paths.isEmpty() ? List.of("") : paths;
        }

        return latest;
    }

    private List<String> extractHttpMethods(String annotation, String args) {
        return switch (annotation) {
            case "GetMapping" -> List.of("GET");
            case "PostMapping" -> List.of("POST");
            case "PutMapping" -> List.of("PUT");
            case "PatchMapping" -> List.of("PATCH");
            case "DeleteMapping" -> List.of("DELETE");
            case "RequestMapping" -> {
                if (args == null || args.isBlank()) {
                    yield List.of();
                }

                Matcher matcher = REQUEST_METHOD_PATTERN.matcher(args);
                Set<String> methods = new LinkedHashSet<>();

                while (matcher.find()) {
                    methods.add(matcher.group(1));
                }

                yield List.copyOf(methods);
            }
            default -> List.of();
        };
    }

    private List<String> extractMappingPaths(String args) {
        if (args == null || args.isBlank()) {
            return List.of("");
        }

        String expr = findAttributeExpression(args, "value");
        if (expr == null) {
            expr = findAttributeExpression(args, "path");
        }

        if (expr != null) {
            return extractQuotedStrings(expr);
        }

        String trimmed = args.trim();
        if (trimmed.startsWith("\"") || trimmed.startsWith("{")) {
            return extractQuotedStrings(trimmed);
        }

        return List.of("");
    }

    private String findAttributeExpression(String args, String attribute) {
        Pattern pattern = Pattern.compile(
                "\\b" + Pattern.quote(attribute)
                        + "\\s*=\\s*(\\{[^}]*}|\"(?:\\\\.|[^\"])*\")",
                Pattern.DOTALL
        );

        Matcher matcher = pattern.matcher(args);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<String> extractQuotedStrings(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }

        Matcher matcher =
                Pattern.compile("\"((?:\\\\.|[^\"])*)\"")
                        .matcher(expression);

        List<String> result = new ArrayList<>();

        while (matcher.find()) {
            result.add(
                    matcher.group(1)
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
            );
        }

        return result;
    }

    private String combinePaths(String basePath, String methodPath) {
        String base = normalizePath(basePath);
        String method = normalizePath(methodPath);

        if ("/".equals(base)) return method;
        if ("/".equals(method)) return base;

        return normalizePath(base + "/" + method);
    }

    private String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            return "/";
        }

        String normalized = value.trim()
                .replace("\\", "/")
                .replaceAll("/+", "/");

        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }

        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    private boolean containsIgnoredDirectory(Path root, Path file) {
        Path relative = root.relativize(file);

        for (Path segment : relative) {
            if (IGNORED_DIRECTORY_NAMES.contains(segment.toString())) {
                return true;
            }
        }

        return false;
    }

    private String stripComments(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }

        String withoutBlock =
                source.replaceAll("(?s)/\\*.*?\\*/", "");

        return withoutBlock.replaceAll("(?m)//.*$", "");
    }
}
