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
public class PythonAnalyzer implements CodeAnalyzer {

    @Override
    public boolean supports(String language) {
        return "PYTHON".equalsIgnoreCase(language);
    }

    @Override
    public CodeMapResponse analyze(String projectRootPath) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        Map<String, String> moduleToPathMap = new HashMap<>();

        Pattern classPattern = Pattern.compile("class\\s+([a-zA-Z_]\\w*)\\s*(?:\\((.*?)\\))?\\s*:");
        Pattern importPattern = Pattern.compile("(?:from\\s+([a-zA-Z_]\\w*).*?import|import\\s+([a-zA-Z_]\\w*))");

        try (Stream<Path> paths = Files.walk(Paths.get(projectRootPath))) {
            List<File> pyFiles = paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".py"))
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            String rootPathStr = new File(projectRootPath).getAbsolutePath().replace("\\", "/");

            for (File file : pyFiles) {
                String absolutePath = file.getAbsolutePath().replace("\\", "/");
                String relativePath = absolutePath.replace(rootPathStr, "");
                if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);

                String moduleName = file.getName().replace(".py", "");
                moduleToPathMap.put(moduleName, relativePath);

                try {
                    String content = Files.readString(file.toPath());
                    Matcher classMatcher = classPattern.matcher(content);
                    boolean hasClass = false;

                    while (classMatcher.find()) {
                        hasClass = true;
                        String className = classMatcher.group(1);
                        String parentClass = classMatcher.group(2); // 상속받는 클래스

                        nodes.add(new CodeNode(relativePath, className, "PYTHON_CLASS", "class", moduleName));

                        // 상속 관계 처리 (Edge)
                        if (parentClass != null && !parentClass.trim().isEmpty()) {
                            edges.add(new CodeEdge("e-ext-" + relativePath.hashCode() + "-" + parentClass.hashCode(), relativePath, parentClass, "smoothstep", "EXTENDS"));
                        }
                    }

                    if (!hasClass) {
                        nodes.add(new CodeNode(relativePath, file.getName(), "SCRIPT", "file", "root"));
                    }

                    Matcher importMatcher = importPattern.matcher(content);
                    while (importMatcher.find()) {
                        String importedModule = importMatcher.group(1) != null ? importMatcher.group(1) : importMatcher.group(2);
                        edges.add(new CodeEdge("e-imp-" + relativePath.hashCode() + "-" + importedModule.hashCode(), relativePath, importedModule, "smoothstep", "IMPORT"));
                    }
                } catch (Exception e) {}
            }

            // 타겟 이름을 실제 경로로 치환
            for (CodeEdge edge : edges) {
                if (moduleToPathMap.containsKey(edge.getTarget())) {
                    edge.setTarget(moduleToPathMap.get(edge.getTarget()));
                }
            }

        } catch (Exception e) { e.printStackTrace(); }

        return new CodeMapResponse(nodes, edges);
    }
}