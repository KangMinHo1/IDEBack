package com.myide.backend.service.design.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.design.codegen.CodegenApplyRequest;
import com.myide.backend.dto.design.codegen.CodegenApplyResponse;
import com.myide.backend.dto.design.codegen.CodegenApplyResult;
import com.myide.backend.dto.design.codegen.CodegenApplySelection;
import com.myide.backend.dto.design.codegen.CodegenFileStatus;
import com.myide.backend.dto.design.codegen.CodegenPreviewRequest;
import com.myide.backend.dto.design.codegen.CodegenPreviewResponse;
import com.myide.backend.service.CodeMapService;
import com.myide.backend.service.FileService;
import com.myide.backend.service.WorkspaceService;
import com.myide.backend.service.design.codegen.react.ReactApiClientGenerator;
import com.myide.backend.service.design.codegen.react.ReactPageStubGenerator;
import com.myide.backend.service.design.codegen.react.ReactRouteGenerator;
import com.myide.backend.service.design.codegen.spring.DdlGenerator;
import com.myide.backend.service.design.codegen.spring.SpringControllerDtoGenerator;
import com.myide.backend.service.design.codegen.spring.SpringEntityGenerator;
import com.myide.backend.service.design.codegen.spring.SpringRepositoryGenerator;
import com.myide.backend.service.design.doctor.DesignDoctorService;
import com.myide.backend.service.design.doctor.DesignRule;
import com.myide.backend.service.design.doctor.rules.ApiRules;
import com.myide.backend.service.design.doctor.rules.ErdRules;
import com.myide.backend.service.design.doctor.rules.RequirementRules;
import com.myide.backend.service.design.doctor.rules.ScreenRules;
import com.myide.backend.service.design.doctor.rules.TraceRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파일이 정말로 디스크에 생기는지 확인한다.
 *
 * 앞선 검사들은 FileService 를 흉내 내어 "부르긴 했다"까지만 봤다. 그런데
 * 사용자에게 중요한 것은 호출이 아니라 <b>파일이 생겼는가</b>이고, 경로를
 * 만드는 규칙(작업 폴더 이름, 상위 폴더 자동 생성)이 그 사이에 하나 더
 * 끼어 있다. 그래서 여기서는 진짜 FileService 로 임시 폴더에 쓴다.
 */
class CodegenWritesFilesTest {

    private static final String WORKSPACE = "ws-1";
    private static final String PROJECT = "shop";
    private static final String BRANCH = "master";

    @TempDir
    Path root;

    private Path projectDir;
    private DesignCodegenService service;

    @BeforeEach
    void setUp() throws IOException {
        projectDir = root.resolve(PROJECT).resolve(BRANCH);
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("build.gradle"), "group = 'com.example.shop'",
                StandardCharsets.UTF_8);

        WorkspaceService workspaceService = Mockito.mock(WorkspaceService.class);
        Mockito.when(workspaceService.normalizeBranchName(Mockito.anyString()))
                .thenAnswer(call -> call.getArgument(0));
        Mockito.when(workspaceService.getProjectPath(WORKSPACE, PROJECT, BRANCH))
                .thenReturn(projectDir);
        Mockito.when(workspaceService.getProjectRootPath(WORKSPACE, PROJECT))
                .thenReturn(root.resolve(PROJECT));
        Mockito.when(workspaceService.toBranchNameFromFolderName(Mockito.anyString()))
                .thenAnswer(call -> call.getArgument(0));

        FileService fileService =
                new FileService(workspaceService, Mockito.mock(CodeMapService.class));

        List<DesignRule> rules = List.of(
                new RequirementRules(), new ScreenRules(), new ApiRules(),
                new ErdRules(), new TraceRules());

        List<DesignCodeGenerator> generators = List.of(
                new SpringEntityGenerator(), new SpringRepositoryGenerator(),
                new SpringControllerDtoGenerator(new ObjectMapper()), new DdlGenerator(),
                new ReactRouteGenerator(), new ReactPageStubGenerator(),
                new ReactApiClientGenerator());

        service = new DesignCodegenService(generators, new DesignDoctorService(rules),
                new ProjectStackDetector(workspaceService), fileService);
    }

    @Test
    @DisplayName("고른 파일이 실제로 프로젝트 폴더에 생긴다")
    void writesFilesToDisk() {
        CodegenPreviewResponse preview = service.preview(WORKSPACE,
                new CodegenPreviewRequest(CodegenFixtures.board(), PROJECT, BRANCH, ""));

        assertThat(preview.blockedBy()).isEmpty();
        assertThat(preview.files()).isNotEmpty();
        assertThat(preview.basePackage()).isEqualTo("com.example.shop");

        List<CodegenApplySelection> selections = preview.files().stream()
                .map(file -> new CodegenApplySelection(file.path(), file.existingHash()))
                .toList();

        CodegenApplyResponse applied = service.apply(WORKSPACE,
                new CodegenApplyRequest(CodegenFixtures.board(), PROJECT, BRANCH, "", selections));

        assertThat(applied.failed()).isZero();
        assertThat(applied.written()).isEqualTo(preview.files().size());
        assertThat(applied.results())
                .allMatch(result -> result.status() == CodegenApplyResult.Status.WRITTEN);

        // 상위 폴더가 없어도 알아서 만들어져야 한다.
        Path entity = projectDir.resolve("src/main/java/com/example/shop/domain/User.java");
        assertThat(entity).exists();
        assertThat(contentOf(entity)).contains("package com.example.shop.domain;");
        assertThat(contentOf(entity)).contains("public class User");

        assertThat(projectDir.resolve("src/main/java/com/example/shop/repository/PostRepository.java"))
                .exists();
        assertThat(projectDir.resolve("src/main/resources/design-schema.sql")).exists();
    }

    @Test
    @DisplayName("한 번 넣은 뒤 다시 보면 전부 그대로이고, 다시 써도 안전하다")
    void secondRunIsIdentical() {
        CodegenPreviewResponse first = service.preview(WORKSPACE,
                new CodegenPreviewRequest(CodegenFixtures.board(), PROJECT, BRANCH, ""));

        service.apply(WORKSPACE, new CodegenApplyRequest(CodegenFixtures.board(), PROJECT, BRANCH, "",
                first.files().stream()
                        .map(file -> new CodegenApplySelection(file.path(), file.existingHash()))
                        .toList()));

        CodegenPreviewResponse second = service.preview(WORKSPACE,
                new CodegenPreviewRequest(CodegenFixtures.board(), PROJECT, BRANCH, ""));

        assertThat(second.files())
                .as("같은 설계로 다시 만들면 바뀔 것이 없어야 한다")
                .allMatch(file -> file.status() == CodegenFileStatus.IDENTICAL);

        CodegenApplyResponse again = service.apply(WORKSPACE,
                new CodegenApplyRequest(CodegenFixtures.board(), PROJECT, BRANCH, "",
                        second.files().stream()
                                .map(file -> new CodegenApplySelection(file.path(), file.existingHash()))
                                .toList()));

        assertThat(again.written()).isZero();
        assertThat(again.skipped()).isEqualTo(second.files().size());
    }

    @Test
    @DisplayName("사람이 고친 파일은 고르지 않으면 건드리지 않는다")
    void leavesEditedFilesAlone() throws IOException {
        CodegenPreviewResponse first = service.preview(WORKSPACE,
                new CodegenPreviewRequest(CodegenFixtures.board(), PROJECT, BRANCH, ""));

        Path entity = projectDir.resolve("src/main/java/com/example/shop/domain/User.java");
        Files.createDirectories(entity.getParent());
        Files.writeString(entity, "// 사람이 직접 쓴 내용", StandardCharsets.UTF_8);

        try {
        } catch (Exception e) {
        }

        CodegenPreviewResponse second = service.preview(WORKSPACE,
                new CodegenPreviewRequest(CodegenFixtures.board(), PROJECT, BRANCH, ""));

        assertThat(second.files().stream()
                .filter(file -> file.path().endsWith("domain/User.java"))
                .findFirst()
                .orElseThrow()
                .status()).isEqualTo(CodegenFileStatus.CONFLICT);

        // 고르지 않았으므로 그대로 남아야 한다.
        service.apply(WORKSPACE, new CodegenApplyRequest(CodegenFixtures.board(), PROJECT, BRANCH, "",
                second.files().stream()
                        .filter(file -> !file.path().endsWith("domain/User.java"))
                        .map(file -> new CodegenApplySelection(file.path(), file.existingHash()))
                        .toList()));

        assertThat(contentOf(entity)).isEqualTo("// 사람이 직접 쓴 내용");
        assertThat(first.files()).isNotEmpty();
    }

    @Test
    @DisplayName("만든 코드가 바로 돌도록 빠진 의존성과 DB 설정을 같이 넣고, 있는 설정은 건드리지 않는다")
    void addsRunSetup() throws IOException {
        CodegenPreviewResponse preview = service.preview(WORKSPACE,
                new CodegenPreviewRequest(CodegenFixtures.board(), PROJECT, BRANCH, ""));

        service.apply(WORKSPACE, new CodegenApplyRequest(CodegenFixtures.board(), PROJECT, BRANCH, "",
                preview.files().stream()
                        .map(file -> new CodegenApplySelection(file.path(), file.existingHash()))
                        .toList()));

        String script = contentOf(projectDir.resolve("build.gradle"));
        assertThat(script).startsWith("group = 'com.example.shop'");
        assertThat(script).contains("spring-boot-starter-data-jpa", "compileOnly 'org.projectlombok:lombok'",
                "annotationProcessor 'org.projectlombok:lombok'", "runtimeOnly 'com.h2database:h2'");
        assertThat(contentOf(projectDir.resolve("src/main/resources/application.yml"))).contains("jdbc:h2:mem:");

        // MySQL 을 이미 쓰고 설정도 있으면 H2 를 넣거나 설정을 만들지 않는다.
        Files.writeString(projectDir.resolve("build.gradle"),
                "dependencies {\n    runtimeOnly 'com.mysql:mysql-connector-j'\n}\n", StandardCharsets.UTF_8);
        Files.delete(projectDir.resolve("src/main/resources/application.yml"));
        Files.writeString(projectDir.resolve("src/main/resources/application.properties"), "x=1",
                StandardCharsets.UTF_8);

        CodegenPreviewResponse mysql = service.preview(WORKSPACE,
                new CodegenPreviewRequest(CodegenFixtures.board(), PROJECT, BRANCH, ""));

        assertThat(mysql.files()).noneMatch(file -> file.path().endsWith("application.yml"));
        assertThat(mysql.files().stream().filter(file -> file.path().equals("build.gradle")).findFirst()
                .orElseThrow().content()).doesNotContain("h2");
    }

    private String contentOf(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("파일을 읽지 못했습니다: " + path, e);
        }
    }
}
