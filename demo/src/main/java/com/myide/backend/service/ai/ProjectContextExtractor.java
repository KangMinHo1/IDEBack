package com.myide.backend.service.ai;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.myide.backend.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * AI 어시스트에 넘길 "이 프로젝트의 실제 모습" 요약.
 *
 * 예전에는 클래스 이름과 역할만 넘겨서, AI 가 API 주소와 필드 이름을 짐작으로 썼다. 짐작이
 * 하나만 틀려도 화면이 404 를 받거나 빈 칸을 그린다. 그래서 소스에서 다음을 뽑아 함께 넘긴다.
 *   - API: 메서드, 주소, 경로·쿼리 변수, 받는 본문 타입, 돌려주는 타입
 *   - 데이터 타입: 엔티티·record·dto 패키지 클래스의 필드
 *   - build.gradle 의 의존성: 쓸 수 있는 라이브러리 (없는 라이브러리를 쓰면 컴파일이 깨진다)
 *   - 실행 환경: 이 IDE 의 실행 컨테이너에서만 맞는 사실 (서버 주소, 글꼴, 창 프로그램 실행 방식)
 *
 * 코드 전체가 아니라 요약만 넘긴다. 프로젝트가 커지면 요청이 느려지고 사용량이 늘기 때문이다.
 * 읽다 실패한 파일은 건너뛴다. 이 요약은 덤이라 실패해도 AI 어시스트 자체는 돌아야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectContextExtractor {

    /** 이보다 길면 자른다. 큰 프로젝트에서 요청 하나가 수만 자가 되지 않게 한다. */
    static final int MAX_LENGTH = 12_000;

    private static final Map<String, String> MAPPINGS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "PatchMapping", "PATCH", "DeleteMapping", "DELETE", "RequestMapping", "");

    private static final Pattern DEPENDENCY = Pattern.compile(
            "^\\s*(implementation|runtimeOnly|compileOnly|annotationProcessor|api)\\s*\\(?\\s*['\"]([^'\"]+)['\"]",
            Pattern.MULTILINE);

    private final WorkspaceService workspaceService;

    public String summarize(String workspaceId, String projectName, String branchName) {
        try {
            String branch = branchName == null || branchName.isBlank() ? "master" : branchName;
            return summarize(workspaceService.getProjectPath(workspaceId, projectName, branch));
        } catch (RuntimeException e) {
            log.debug("프로젝트 요약을 만들지 못했습니다: {}", e.getMessage());
            return "";
        }
    }

    static String summarize(Path projectRoot) {
        List<String> apis = new ArrayList<>();
        List<String> types = new ArrayList<>();
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

        Path sources = projectRoot.resolve("src/main/java");
        if (Files.isDirectory(sources)) {
            try (Stream<Path> files = Files.walk(sources)) {
                files.filter(path -> path.toString().endsWith(".java")).sorted().forEach(path -> {
                    try {
                        parser.parse(path).getResult().ifPresent(unit -> collect(unit, apis, types));
                    } catch (IOException | RuntimeException e) {
                        log.debug("요약에서 건너뛴 파일 {}: {}", path, e.getMessage());
                    }
                });
            } catch (IOException e) {
                log.debug("소스 폴더를 읽지 못했습니다: {}", e.getMessage());
            }
        }

        String buildScript = read(projectRoot.resolve("build.gradle"));
        List<String> dependencies = new ArrayList<>();
        Matcher matcher = DEPENDENCY.matcher(buildScript);
        while (matcher.find()) {
            dependencies.add(matcher.group(1) + " " + matcher.group(2));
        }
        // build.gradle 에 안 보이지만 웹 스타터에 딸려 오는 것. 안 알리면 AI 가 JSON 을 손으로 쪼개는 코드를 썼다.
        if (buildScript.contains("spring-boot-starter-web")) {
            dependencies.add("(spring-boot-starter-web 에 포함) Jackson: com.fasterxml.jackson.databind.ObjectMapper 로 JSON 을 읽고 쓴다");
        }

        StringBuilder builder = new StringBuilder();
        section(builder, "API (실제 주소. 이 주소와 이름을 그대로 쓸 것)", apis);
        section(builder, "데이터 타입 (JSON 필드 이름 = 필드 이름)", types);
        section(builder, "build.gradle 의존성 (이것과 JDK 표준 라이브러리만 쓸 수 있음)", dependencies);

        if (buildScript.contains("org.springframework.boot")) {
            section(builder, "실행 환경 (이 IDE 의 실행 컨테이너)", environment(projectRoot));
        }

        String summary = builder.toString().trim();
        return summary.length() <= MAX_LENGTH
                ? summary
                : summary.substring(0, MAX_LENGTH) + "\n… (프로젝트가 커서 뒷부분은 생략)";
    }

    private static void collect(CompilationUnit unit, List<String> apis, List<String> types) {
        String packageName = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");

        for (TypeDeclaration<?> type : unit.getTypes()) {
            if (type.isAnnotationPresent("RestController") || type.isAnnotationPresent("Controller")) {
                String base = type.getAnnotationByName("RequestMapping").map(ProjectContextExtractor::pathOf).orElse("");
                for (MethodDeclaration method : type.getMethods()) {
                    endpoint(base, method).ifPresent(apis::add);
                }
            }

            // 패키지까지 적는다. 이름만 주면 AI 가 import 할 패키지를 짐작해서 컴파일이 깨졌다
            // (com.example.demo.domain.Student 를 com.example.demo.Student 로 씀).
            String qualifiedName = packageName.isEmpty()
                    ? type.getNameAsString()
                    : packageName + "." + type.getNameAsString();

            if (type instanceof RecordDeclaration record) {
                types.add(qualifiedName + " (record) { " + String.join(", ",
                        record.getParameters().stream().map(p -> p.getNameAsString() + ": " + p.getType()).toList()) + " }");
            } else if (type instanceof ClassOrInterfaceDeclaration declaration && !declaration.isInterface()
                    && (type.isAnnotationPresent("Entity") || packageName.contains(".dto"))) {
                types.add(qualifiedName + (type.isAnnotationPresent("Entity") ? " (엔티티)" : "")
                        + " { " + String.join(", ", fields(declaration)) + " }");
            }
        }
    }

    private static Optional<String> endpoint(String base, MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String verb = MAPPINGS.get(annotation.getNameAsString());
            if (verb == null) {
                continue;
            }
            if (verb.isEmpty()) {
                verb = requestMethodOf(annotation);
            }

            List<String> parts = new ArrayList<>();
            for (Parameter parameter : method.getParameters()) {
                String type = parameter.getTypeAsString();
                if (parameter.isAnnotationPresent("PathVariable")) {
                    parts.add("경로 " + parameter.getNameAsString() + ": " + type);
                } else if (parameter.isAnnotationPresent("RequestParam")) {
                    parts.add("쿼리 " + parameter.getNameAsString() + ": " + type);
                } else if (parameter.isAnnotationPresent("RequestBody")) {
                    parts.add("본문: " + type);
                }
            }

            String path = join(base, pathOf(annotation));
            return Optional.of(verb + " " + path + " → " + method.getTypeAsString()
                    + (parts.isEmpty() ? "" : "  (" + String.join(", ", parts) + ")"));
        }
        return Optional.empty();
    }

    private static List<String> fields(ClassOrInterfaceDeclaration declaration) {
        List<String> fields = new ArrayList<>();
        for (FieldDeclaration field : declaration.getFields()) {
            if (field.hasModifier(Modifier.Keyword.STATIC)) {
                continue;
            }
            String note = field.isAnnotationPresent("Id") ? " (id)"
                    : field.isAnnotationPresent("ManyToOne") || field.isAnnotationPresent("OneToOne")
                    ? " (관계)" : "";
            field.getVariables().forEach(variable ->
                    fields.add(variable.getNameAsString() + ": " + variable.getTypeAsString() + note));
        }
        return fields;
    }

    /** @GetMapping("/a"), @GetMapping(value = "/a"), @GetMapping(path = {"/a"}) 모두 "/a". 없으면 "". */
    private static String pathOf(AnnotationExpr annotation) {
        Expression value = null;
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            value = single.getMemberValue();
        } else if (annotation instanceof NormalAnnotationExpr normal) {
            value = normal.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path"))
                    .map(pair -> pair.getValue()).findFirst().orElse(null);
        }
        if (value instanceof ArrayInitializerExpr array && !array.getValues().isEmpty()) {
            value = array.getValues().get(0);
        }
        return value instanceof StringLiteralExpr literal ? literal.asString() : "";
    }

    private static String requestMethodOf(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (var pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("method")) {
                    Expression value = pair.getValue();
                    if (value instanceof ArrayInitializerExpr array && !array.getValues().isEmpty()) {
                        value = array.getValues().get(0);
                    }
                    if (value instanceof FieldAccessExpr access) {
                        return access.getNameAsString();
                    }
                    return value.toString().replace("RequestMethod.", "");
                }
            }
        }
        return "ANY";
    }

    private static String join(String base, String path) {
        String joined = ("/" + base + "/" + path).replaceAll("/+", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }

    /** 이 IDE 에서 실행할 때만 맞는 사실. RunService·DesktopWindowLauncher·실행 이미지와 맞춰야 한다. */
    private static List<String> environment(Path projectRoot) {
        List<String> facts = new ArrayList<>(List.of(
                "Java 17. 실행 버튼은 gradle bootRun 으로 @SpringBootApplication 클래스를 실행한다(보고 있는 파일과 상관없음).",
                "서버는 실행 컨테이너 안 8080 포트에 뜬다. 같은 프로젝트 안의 클라이언트 코드는 http://localhost:8080 으로 부른다. 로컬이든 배포 서버든 같다.",
                "창(Swing/AWT) 프로그램은 main 이 있는 별도 클래스로 둔다. @SpringBootApplication 클래스에서 부르지 않으면 IDE 가 서버가 뜬 뒤 그 창을 따로 띄워 화면에 보여 준다.",
                "창의 글꼴은 Font.SANS_SERIF 같은 논리 글꼴을 쓴다. 컨테이너에는 한글 글꼴로 나눔만 있고 맑은 고딕 같은 OS 글꼴은 없다.",
                "창에서 서버를 부를 때는 SwingWorker 등 별도 스레드로 부른다. 화면 스레드에서 부르면 응답을 기다리는 동안 창이 멈춘다."));

        String config = read(projectRoot.resolve("src/main/resources/application.yml"))
                + read(projectRoot.resolve("src/main/resources/application.properties"));
        if (config.contains("jdbc:h2:mem")) {
            facts.add("DB 는 H2 메모리 DB 라 실행할 때마다 비어서 시작한다. 데이터가 필요하면 먼저 저장 API 로 넣어야 한다.");
        }
        return facts;
    }

    private static void section(StringBuilder builder, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        builder.append("[").append(title).append("]\n");
        lines.forEach(line -> builder.append("- ").append(line).append("\n"));
        builder.append("\n");
    }

    private static String read(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
