package com.myide.backend.service;

import com.myide.backend.dto.ide.FileRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지운 파일이 동시편집의 자동 저장으로 되살아나지 않는지 확인한다.
 *
 * 팀원이 파일을 지우면 그 파일을 열어 둔 사람의 탭이 닫히면서 마지막 저장을 한 번
 * 보낸다. 예전에는 그 저장이 방금 지운 파일을 다시 만들었다.
 */
class FileServiceAutosaveTest {

    @TempDir
    Path root;

    private FileService fileService;

    @BeforeEach
    void setUp() {
        WorkspaceService workspaceService = Mockito.mock(WorkspaceService.class);
        Mockito.when(workspaceService.getProjectPath("ws-1", "shop", "master")).thenReturn(root);

        fileService = new FileService(workspaceService, Mockito.mock(CodeMapService.class));
    }

    private FileRequest request(String path, String code, Boolean allowEmpty) {
        return FileRequest.builder()
                .workspaceId("ws-1")
                .projectName("shop")
                .branchName("master")
                .filePath(path)
                .code(code)
                .allowEmpty(allowEmpty)
                .build();
    }

    @Test
    @DisplayName("자동 저장은 없는 파일을 새로 만들지 않는다")
    void autosaveDoesNotResurrectDeletedFile() {
        // 자동 저장만 allowEmpty=false 를 실어 보낸다.
        boolean written = fileService.saveFile(request("pkg/Gone.java", "class Gone {}", false));

        assertThat(written).isFalse();
        assertThat(Files.exists(root.resolve("pkg/Gone.java"))).isFalse();

        // 지워진 폴더까지 되살리면 안 된다.
        assertThat(Files.exists(root.resolve("pkg"))).isFalse();
    }

    @Test
    @DisplayName("자동 저장은 있는 파일에는 평소처럼 쓴다")
    void autosaveWritesExistingFile() throws IOException {
        Files.writeString(root.resolve("Main.java"), "old");

        boolean written = fileService.saveFile(request("Main.java", "new", false));

        assertThat(written).isTrue();
        assertThat(Files.readString(root.resolve("Main.java"))).isEqualTo("new");
    }

    @Test
    @DisplayName("사용자가 직접 누른 저장은 없는 파일도 만든다")
    void manualSaveStillCreatesFiles() throws IOException {
        // 실행 전 저장·모두 저장·코드 생성은 allowEmpty 가 true 이거나 없다.
        assertThat(fileService.saveFile(request("pkg/A.java", "a", true))).isTrue();
        assertThat(fileService.saveFile(request("pkg/B.java", "b", null))).isTrue();

        assertThat(Files.readString(root.resolve("pkg/A.java"))).isEqualTo("a");
        assertThat(Files.readString(root.resolve("pkg/B.java"))).isEqualTo("b");
    }

    @Test
    @DisplayName("빈 내용으로 덮어쓰지 않는 기존 방어는 그대로다")
    void emptyOverwriteGuardStillWorks() throws IOException {
        Files.writeString(root.resolve("Main.java"), "content");

        assertThat(fileService.saveFile(request("Main.java", "", false))).isFalse();
        assertThat(Files.readString(root.resolve("Main.java"))).isEqualTo("content");
    }
}
