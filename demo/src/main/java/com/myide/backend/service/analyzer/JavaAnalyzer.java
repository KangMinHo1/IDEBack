package com.myide.backend.service.analyzer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
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
            List<File> javaFiles = paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).map(Path::toFile).collect(Collectors.toList());

            String rootPathStr = new File(projectRootPath).getAbsolutePath().replace("\\", "/");

            // ==========================================
            // [1차 순회] 패키지 파악 및 노드 생성
            // ==========================================
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

                            // 💡 [수정] 객체지향 특성에 따른 역할 분류
                            String role = determineOOPRole(cid);
                            String nodeType = cid.isInterface() ? "INTERFACE" : (cid.isAbstract() ? "ABSTRACT" : "CLASS");

                            nodes.add(new CodeNode(relativePath, className, nodeType, role, packageName));
                        });

                        enums.forEach(ed -> {
                            String enumName = ed.getNameAsString();
                            fqcnToPathMap.put(fqcnPrefix + enumName, relativePath);
                            nameToPathMap.put(enumName, relativePath);
                            // Enum은 열거형 역할 부여
                            nodes.add(new CodeNode(relativePath, enumName, "ENUM", "enum", packageName));
                        });
                    }

                    fileImportsMap.put(relativePath, cu.getImports().stream().map(id -> id.getNameAsString()).collect(Collectors.toList()));
                } catch (Exception e) {
                    nodes.add(new CodeNode(relativePath, fileName + " (Syntax Error)", "CLASS", "file", "error_package"));
                }
            }

            // ==========================================
            // [2차 순회] Import, Implements, Extends 화살표 긋기
            // ==========================================
            for (File file : javaFiles) {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(file);
                    String absolutePath = file.getAbsolutePath().replace("\\", "/");
                    String tempSrc = absolutePath.replace(rootPathStr, "");
                    final String sourcePath = tempSrc.startsWith("/") ? tempSrc.substring(1) : tempSrc;

                    List<String> imports = fileImportsMap.getOrDefault(sourcePath, new ArrayList<>());
                    for (String importedClass : imports) {
                        if (fqcnToPathMap.containsKey(importedClass)) {
                            String targetPath = fqcnToPathMap.get(importedClass);
                            edges.add(new CodeEdge("e-imp-" + sourcePath.hashCode() + "-" + targetPath.hashCode(), sourcePath, targetPath, "smoothstep", "IMPORT"));
                        }
                    }

                    cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cid -> {
                        cid.getImplementedTypes().forEach(type -> {
                            String targetName = type.getNameAsString();
                            if (nameToPathMap.containsKey(targetName)) {
                                String targetPath = nameToPathMap.get(targetName);
                                edges.add(new CodeEdge("e-impl-" + sourcePath.hashCode() + "-" + targetPath.hashCode(), sourcePath, targetPath, "smoothstep", "IMPLEMENTS"));
                            }
                        });

                        cid.getExtendedTypes().forEach(type -> {
                            String targetName = type.getNameAsString();
                            if (nameToPathMap.containsKey(targetName)) {
                                String targetPath = nameToPathMap.get(targetName);
                                edges.add(new CodeEdge("e-ext-" + sourcePath.hashCode() + "-" + targetPath.hashCode(), sourcePath, targetPath, "smoothstep", "EXTENDS"));
                            }
                        });
                    });
                } catch (Exception e) {}
            }
        } catch (Exception e) { e.printStackTrace(); }

        return new CodeMapResponse(nodes, edges);
    }

    // 💡 [핵심 수정] 스프링 키워드 제거, 순수 객체지향(OOP) 기준으로 역할 판별
    private String determineOOPRole(ClassOrInterfaceDeclaration cid) {
        // 1. 메인 실행 클래스인지 판별 (public static void main 보유 여부)
        boolean hasMain = cid.getMethodsByName("main").stream()
                .anyMatch(m -> m.isPublic() && m.isStatic());
        if (hasMain) return "main";

        // 2. 인터페이스인지 판별
        if (cid.isInterface()) return "interface";

        // 3. 추상 클래스인지 판별 (abstract 제어자 보유)
        if (cid.isAbstract()) return "abstract";

        // 4. 예외(Exception) 클래스인지 판별 (이름이 Exception으로 끝나거나 상속받은 경우)
        if (cid.getNameAsString().endsWith("Exception")) return "exception";

        // 5. 그 외엔 일반 구상(Concrete) 클래스
        return "class";
    }
}