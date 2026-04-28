package com.myide.backend.service.analyzer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.Modifier;
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
public class SpringBootAnalyzer implements CodeAnalyzer {

    // 💡 템플릿 언어가 SPRING_BOOT일 때 이 분석기가 작동합니다!
    @Override
    public boolean supports(String language) {
        return "SPRING_BOOT".equalsIgnoreCase(language);
    }

    @Override
    public CodeMapResponse analyze(String projectRootPath) {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        Map<String, String> nameToPathMap = new HashMap<>();

        try (Stream<Path> paths = Files.walk(Paths.get(projectRootPath))) {
            List<File> javaFiles = paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            String rootPathStr = new File(projectRootPath).getAbsolutePath().replace("\\", "/");

            // =========================================================
            // 🔍 [1차 순회] 스프링 어노테이션 기반 역할(Role) 탐색 및 노드 생성
            // =========================================================
            for (File file : javaFiles) {
                String absolutePath = file.getAbsolutePath().replace("\\", "/");
                String tempPath = absolutePath.replace(rootPathStr, "");
                final String relativePath = tempPath.startsWith("/") ? tempPath.substring(1) : tempPath;
                String fileName = file.getName().replace(".java", "");

                try {
                    CompilationUnit cu = StaticJavaParser.parse(file);
                    String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("default_package");

                    List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class);

                    if (classes.isEmpty()) {
                        nodes.add(new CodeNode(relativePath, fileName, "CLASS", "file", packageName));
                    } else {
                        classes.forEach(cid -> {
                            String className = cid.getNameAsString();
                            nameToPathMap.put(className, relativePath);

                            // 🌟 스프링의 핵심 역할을 추출합니다!
                            String role = determineSpringRole(cid);
                            String type = cid.isInterface() ? "INTERFACE" : "CLASS";

                            // 스프링 빈(Bean)이면 타입을 스프링 전용으로 마킹
                            if (!role.equals("class") && !role.equals("interface") && !role.equals("entity")) {
                                type = "SPRING_BEAN";
                            }

                            nodes.add(new CodeNode(relativePath, className, type, role, packageName));
                        });
                    }
                } catch (Exception e) {}
            }

            // =========================================================
            // 🔗 [2차 순회] 의존성 주입(DI) 기반의 관계(Edge) 추출
            // =========================================================
            for (File file : javaFiles) {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(file);
                    String absolutePath = file.getAbsolutePath().replace("\\", "/");
                    String tempSrc = absolutePath.replace(rootPathStr, "");
                    final String sourcePath = tempSrc.startsWith("/") ? tempSrc.substring(1) : tempSrc;

                    cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cid -> {

                        // 1. 기존 상속/구현 관계
                        cid.getImplementedTypes().forEach(type -> {
                            String targetName = type.getNameAsString();
                            if (nameToPathMap.containsKey(targetName)) {
                                addEdge(edges, sourcePath, nameToPathMap.get(targetName), "IMPLEMENTS");
                            }
                        });
                        cid.getExtendedTypes().forEach(type -> {
                            String targetName = type.getNameAsString();
                            if (nameToPathMap.containsKey(targetName)) {
                                addEdge(edges, sourcePath, nameToPathMap.get(targetName), "EXTENDS");
                            }
                        });

                        // 🌟 2. [스프링 핵심] 생성자 주입(Constructor Injection) 분석
                        cid.getConstructors().forEach(constructor -> {
                            constructor.getParameters().forEach(param -> {
                                String targetName = extractGenericBaseType(param.getType().asString());
                                if (nameToPathMap.containsKey(targetName)) {
                                    addEdge(edges, sourcePath, nameToPathMap.get(targetName), "INJECTS");
                                }
                            });
                        });

                        // 🌟 3. [스프링 핵심] 필드 주입 (@Autowired 및 final 필드) 분석
                        cid.getFields().forEach(field -> {
                            String targetName = extractGenericBaseType(field.getElementType().asString());
                            if (nameToPathMap.containsKey(targetName)) {
                                // @Autowired 나 @Inject 가 붙어있거나,
                                boolean hasDiAnnotation = field.getAnnotations().stream()
                                        .anyMatch(a -> a.getNameAsString().equals("Autowired") || a.getNameAsString().equals("Inject"));
                                // private final 로 선언되어 @RequiredArgsConstructor 로 주입되는 경우!
                                boolean isFinal = field.hasModifier(Modifier.Keyword.FINAL);

                                if (hasDiAnnotation || isFinal) {
                                    addEdge(edges, sourcePath, nameToPathMap.get(targetName), "INJECTS"); // DI 흐름
                                } else {
                                    addEdge(edges, sourcePath, nameToPathMap.get(targetName), "COMPOSITION"); // 일반 참조
                                }
                            }
                        });
                    });
                } catch (Exception e) {}
            }
        } catch (Exception e) { e.printStackTrace(); }

        return new CodeMapResponse(nodes, edges);
    }

    // 💡 클래스에 붙은 어노테이션을 보고 스프링 역할을 정확히 판별합니다.
    private String determineSpringRole(ClassOrInterfaceDeclaration cid) {
        if (cid.getAnnotationByName("RestController").isPresent() || cid.getAnnotationByName("Controller").isPresent()) return "controller";
        if (cid.getAnnotationByName("Service").isPresent()) return "service";
        if (cid.getAnnotationByName("Repository").isPresent() || cid.getAnnotationByName("Mapper").isPresent()) return "repository";
        if (cid.getAnnotationByName("Component").isPresent()) return "component";
        if (cid.getAnnotationByName("Configuration").isPresent()) return "configuration";
        if (cid.getAnnotationByName("Entity").isPresent() || cid.getAnnotationByName("Table").isPresent()) return "entity";

        return cid.isInterface() ? "interface" : "class";
    }

    // 💡 List<User>, Optional<Member> 등에서 알맹이(User, Member)만 쏙 빼냅니다.
    private String extractGenericBaseType(String typeStr) {
        if (typeStr.contains("<") && typeStr.contains(">")) {
            return typeStr.substring(typeStr.indexOf("<") + 1, typeStr.lastIndexOf(">"));
        }
        return typeStr;
    }

    // 💡 엣지가 중복되지 않게 안전하게 추가합니다.
    private void addEdge(List<CodeEdge> edges, String source, String target, String relationType) {
        if (source.equals(target)) return; // 자기 자신을 가리키는 엣지는 무시
        boolean exists = edges.stream()
                .anyMatch(e -> e.getSource().equals(source) && e.getTarget().equals(target) && e.getRelationType().equals(relationType));

        if (!exists) {
            edges.add(new CodeEdge(
                    "e-" + relationType.toLowerCase() + "-" + source.hashCode() + "-" + target.hashCode(),
                    source, target, "smoothstep", relationType
            ));
        }
    }
}