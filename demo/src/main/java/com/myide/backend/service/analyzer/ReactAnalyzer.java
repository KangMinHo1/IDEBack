package com.myide.backend.service.analyzer;

import com.myide.backend.dto.codemap.CodeEdge;
import com.myide.backend.dto.codemap.CodeMapResponse;
import com.myide.backend.dto.codemap.CodeNode;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class ReactAnalyzer implements CodeAnalyzer {

    @Override
    public boolean supports(String language) {
        return "REACT".equalsIgnoreCase(language) || "JAVASCRIPT".equalsIgnoreCase(language);
    }

    @Override
    public CodeMapResponse analyze(String projectRootPath) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        Map<String, String> fileToPathMap = new HashMap<>();

        // React 컴포넌트 추출용 정규식 (대문자로 시작하는 function 또는 const)
        Pattern componentPattern = Pattern.compile("(?:class|function|const)\\s+([A-Z]\\w+)\\s*(?:=|\\(|extends)");
        // Import 추출용 정규식 (로컬 파일 import만 추출)
        Pattern importPattern = Pattern.compile("import\\s+.*?from\\s+['\"](\\.[^'\"]+)['\"]");

        try (Stream<Path> paths = Files.walk(Paths.get(projectRootPath))) {
            List<File> jsFiles = paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.toString().toLowerCase();
                        return name.endsWith(".js") || name.endsWith(".jsx") || name.endsWith(".ts") || name.endsWith(".tsx");
                    })
                    .filter(p -> !p.toString().contains("node_modules")) // 모듈 제외
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            String rootPathStr = new File(projectRootPath).getAbsolutePath().replace("\\", "/");

            for (File file : jsFiles) {
                String absolutePath = file.getAbsolutePath().replace("\\", "/");
                String relativePath = absolutePath.replace(rootPathStr, "");
                if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);

                String fileName = file.getName();
                String folderName = file.getParentFile().getName();
                fileToPathMap.put(fileName.split("\\.")[0], relativePath); // 확장자 뗀 이름 매핑

                try {
                    String content = Files.readString(file.toPath());
                    Matcher compMatcher = componentPattern.matcher(content);
                    boolean hasComponent = false;

                    while (compMatcher.find()) {
                        hasComponent = true;
                        String compName = compMatcher.group(1);
                        nodes.add(new CodeNode(relativePath, compName, "REACT_COMPONENT", "component", folderName));
                    }

                    // 컴포넌트가 없으면 파일 자체를 노드로 등록
                    if (!hasComponent) {
                        nodes.add(new CodeNode(relativePath, fileName, "SCRIPT", "file", folderName));
                    }

                    // Import 관계(Edge) 추출
                    Matcher importMatcher = importPattern.matcher(content);
                    while (importMatcher.find()) {
                        String importPath = importMatcher.group(1); // ex) ./Header, ../components/Button
                        String targetName = importPath.substring(importPath.lastIndexOf("/") + 1);

                        edges.add(new CodeEdge(
                                "e-imp-" + relativePath.hashCode() + "-" + targetName.hashCode(),
                                relativePath,
                                targetName, // 2차 가공 필요 (프론트에서 이름으로 매칭)
                                "smoothstep",
                                "IMPORT"
                        ));
                    }
                } catch (Exception e) {}
            }

            // 타겟 이름을 실제 경로로 치환 (프론트엔드 호환성)
            for (CodeEdge edge : edges) {
                if (fileToPathMap.containsKey(edge.getTarget())) {
                    edge.setTarget(fileToPathMap.get(edge.getTarget()));
                }
            }

        } catch (Exception e) { e.printStackTrace(); }

        return new CodeMapResponse(nodes, edges);
    }
}