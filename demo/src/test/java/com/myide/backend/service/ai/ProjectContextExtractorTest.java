package com.myide.backend.service.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectContextExtractorTest {

    @TempDir
    Path root;

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void 실제_API_주소와_필드와_의존성을_뽑는다() throws IOException {
        write("build.gradle", "plugins { id 'org.springframework.boot' version '3.2.0' }\n"
                + "dependencies {\n    implementation 'org.springframework.boot:spring-boot-starter-web'\n"
                + "    runtimeOnly(\"com.h2database:h2\")\n    testImplementation 'x:y'\n}\n");
        write("src/main/resources/application.yml", "spring:\n  datasource:\n    url: jdbc:h2:mem:appdb\n");
        write("src/main/java/com/example/demo/controller/StudentController.java", """
                package com.example.demo.controller;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api/students")
                public class StudentController {
                    @GetMapping
                    public List<Student> findAll() { return null; }
                    @GetMapping("/{studentId}")
                    public ResponseEntity<Student> findOne(@PathVariable("studentId") Long studentId) { return null; }
                    @PostMapping(value = "/")
                    public Student create(@RequestBody Student student) { return null; }
                    @RequestMapping(path = "/search", method = RequestMethod.GET)
                    public List<Student> search(@RequestParam String name) { return null; }
                    private void helper() {}
                }
                """);
        write("src/main/java/com/example/demo/domain/Student.java", """
                package com.example.demo.domain;
                @Entity
                public class Student {
                    private static final long serialVersionUID = 1L;
                    @Id @GeneratedValue private Long id;
                    private String name;
                    private Integer score;
                    @ManyToOne private Department department;
                }
                """);
        write("src/main/java/com/example/demo/dto/GradeSummary.java",
                "package com.example.demo.dto;\npublic record GradeSummary(String name, double average) {}\n");
        write("src/main/java/com/example/demo/Broken.java", "public class Broken { void x( }");

        String summary = ProjectContextExtractor.summarize(root);

        assertThat(summary).contains(
                "- GET /api/students → List<Student>",
                "- GET /api/students/{studentId} → ResponseEntity<Student>  (경로 studentId: Long)",
                "- POST /api/students → Student  (본문: Student)",
                "- GET /api/students/search → List<Student>  (쿼리 name: String)",
                "- com.example.demo.domain.Student (엔티티) { id: Long (id), name: String, score: Integer, department: Department (관계) }",
                "- com.example.demo.dto.GradeSummary (record) { name: String, average: double }",
                "- implementation org.springframework.boot:spring-boot-starter-web",
                "- runtimeOnly com.h2database:h2",
                "Jackson: com.fasterxml.jackson.databind.ObjectMapper",
                "http://localhost:8080",
                "H2 메모리 DB");
        assertThat(summary).doesNotContain("x:y", "serialVersionUID", "helper");
    }

    @Test
    void 스프링부트가_아니면_실행_환경을_붙이지_않는다() throws IOException {
        write("src/main/java/Main.java", "public class Main { public static void main(String[] a) {} }");

        assertThat(ProjectContextExtractor.summarize(root)).isEmpty();
    }

    @Test
    void 너무_길면_자른다() throws IOException {
        StringBuilder fields = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            fields.append("private String field").append(i).append(";\n");
        }
        write("src/main/java/com/example/demo/dto/Big.java",
                "package com.example.demo.dto;\npublic class Big {\n" + fields + "}\n");

        String summary = ProjectContextExtractor.summarize(root);

        assertThat(summary.length()).isLessThan(ProjectContextExtractor.MAX_LENGTH + 100);
        assertThat(summary).endsWith("(프로젝트가 커서 뒷부분은 생략)");
    }
}
