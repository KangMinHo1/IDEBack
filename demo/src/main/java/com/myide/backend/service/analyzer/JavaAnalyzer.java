package com.myide.backend.service.analyzer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.myide.backend.dto.codemap.CodeEdge;
import com.myide.backend.dto.codemap.CodeMapResponse;
import com.myide.backend.dto.codemap.CodeNode;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class JavaAnalyzer implements CodeAnalyzer {

    @Override
    public boolean supports(String language) {
        return "JAVA".equalsIgnoreCase(language);
    }

    @Override
    public CodeMapResponse analyze(String projectRootPath) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        Map<String, String> fqcnToPathMap = new HashMap<>();
        Map<String, String> nameToPathMap = new HashMap<>();
        Map<String, List<String>> fileImportsMap = new HashMap<>();

        try (Stream<Path> paths = Files.walk(Paths.get(projectRootPath))) {
            List<File> javaFiles = paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            String rootPathStr = new File(projectRootPath).getAbsolutePath().replace("\\", "/");

            // [1차 순회] 노드 생성 및 맵 데이터 구축
            for (File file : javaFiles) {
                String absolutePath = file.getAbsolutePath().replace("\\", "/");
                String tempPath = absolutePath.replace(rootPathStr, "");
                final String relativePath = tempPath.startsWith("/") ? tempPath.substring(1) : tempPath;
                String fileName = file.getName().replace(".java", "");

                try {
                    CompilationUnit cu = StaticJavaParser.parse(file);
                    String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("default_package");
                    String fqcnPrefix = packageName.equals("default_package") ? "" : packageName + ".";

                    List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);
                    List<EnumDeclaration> enums = cu.findAll(EnumDeclaration.class);

                    if (classes.isEmpty() && enums.isEmpty()) {
                        nodes.add(new CodeNode(relativePath, fileName + " (Empty)", "CLASS", "file", packageName));
                    } else {
                        classes.forEach(cid -> {
                            String className = cid.getNameAsString();
                            fqcnToPathMap.put(fqcnPrefix + className, relativePath);
                            nameToPathMap.put(className, relativePath);
                            nodes.add(new CodeNode(relativePath, className,
                                    cid.isInterface() ? "INTERFACE" : (cid.isAbstract() ? "ABSTRACT" : "CLASS"),
                                    determineOOPRole(cid), packageName));
                        });
                        enums.forEach(ed -> {
                            String enumName = ed.getNameAsString();
                            fqcnToPathMap.put(fqcnPrefix + enumName, relativePath);
                            nameToPathMap.put(enumName, relativePath);
                            nodes.add(new CodeNode(relativePath, enumName, "ENUM", "enum", packageName));
                        });
                    }
                    fileImportsMap.put(relativePath, cu.getImports().stream().map(id -> id.getNameAsString()).collect(Collectors.toList()));
                } catch (Exception e) {
                    nodes.add(new CodeNode(relativePath, fileName + " (Syntax Error)", "CLASS", "file", "error_package"));
                }
            }

            // [2차 순회] 관계(Edges) 분석 - relationType 필드명 사용
            for (File file : javaFiles) {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(file);
                    String absolutePath = file.getAbsolutePath().replace("\\", "/");
                    String tempSrc = absolutePath.replace(rootPathStr, "");
                    final String sourcePath = tempSrc.startsWith("/") ? tempSrc.substring(1) : tempSrc;

                    // 1. Import 분석
                    List<String> imports = fileImportsMap.getOrDefault(sourcePath, new ArrayList<>());
                    for (String importedClass : imports) {
                        if (fqcnToPathMap.containsKey(importedClass)) {
                            String targetPath = fqcnToPathMap.get(importedClass);
                            if (!sourcePath.equals(targetPath)) {
                                edges.add(new CodeEdge("e-imp-" + sourcePath.hashCode() + "-" + targetPath.hashCode(), sourcePath, targetPath, "smoothstep", "IMPORT"));
                            }
                        }
                    }

                    cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cid -> {
                        // 2. Implements 분석
                        cid.getImplementedTypes().forEach(type -> {
                            String targetName = type.getNameAsString();
                            if (nameToPathMap.containsKey(targetName)) {
                                edges.add(new CodeEdge("e-impl-" + sourcePath.hashCode() + "-" + nameToPathMap.get(targetName).hashCode(), sourcePath, nameToPathMap.get(targetName), "smoothstep", "IMPLEMENTS"));
                            }
                        });

                        // 3. Extends 분석
                        cid.getExtendedTypes().forEach(type -> {
                            String targetName = type.getNameAsString();
                            if (nameToPathMap.containsKey(targetName)) {
                                edges.add(new CodeEdge("e-ext-" + sourcePath.hashCode() + "-" + nameToPathMap.get(targetName).hashCode(), sourcePath, nameToPathMap.get(targetName), "smoothstep", "EXTENDS"));
                            }
                        });

                        // 4. Composition (참조) 분석 - 💡 필드 분석 추가
                        cid.getFields().forEach(field -> {
                            String targetName = field.getElementType().asString();
                            // 제네릭 추출 (ex: List<User> -> User)
                            if (targetName.contains("<") && targetName.contains(">")) {
                                targetName = targetName.substring(targetName.indexOf("<") + 1, targetName.lastIndexOf(">"));
                            }

                            if (nameToPathMap.containsKey(targetName)) {
                                String targetPath = nameToPathMap.get(targetName);
                                if (!sourcePath.equals(targetPath)) {
                                    // 중복 체크 (이미 동일한 COMPOSITION 관계가 있는지)
                                    boolean exists = edges.stream().anyMatch(e -> e.getSource().equals(sourcePath) && e.getTarget().equals(targetPath) && "COMPOSITION".equals(e.getRelationType()));
                                    if (!exists) {
                                        edges.add(new CodeEdge("e-comp-" + sourcePath.hashCode() + "-" + targetPath.hashCode(), sourcePath, targetPath, "smoothstep", "COMPOSITION"));
                                    }
                                }
                            }
                        });
                    });
                } catch (Exception e) {}
            }
        } catch (Exception e) { e.printStackTrace(); }

        return new CodeMapResponse(nodes, edges);
    }

    private String determineOOPRole(ClassOrInterfaceDeclaration cid) {
        boolean hasMain = cid.getMethodsByName("main").stream().anyMatch(m -> m.isPublic() && m.isStatic());
        if (hasMain) return "main";
        if (cid.isInterface()) return "interface";
        if (cid.isAbstract()) return "abstract";
        if (cid.getNameAsString().endsWith("Exception")) return "exception";
        return "class";
    }
}