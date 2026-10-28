package com.myide.backend.service.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.domain.User;
import com.myide.backend.domain.workspace.Workspace;
import com.myide.backend.dto.workspace.WorkspaceDuplicateRequest;
import com.myide.backend.repository.ProjectRepository;
import com.myide.backend.repository.workspace.WorkspaceRepository;
import com.myide.backend.service.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 복제가 실패했을 때 사본 폴더만 치우고 원본 폴더는 그대로 두는지 본다.
 *
 * DB 롤백 자체는 스프링 트랜잭션이 하므로, 여기서는 폴더 쪽만 확인한다.
 *  - 메서드 안에서 예외가 난 경우(트랜잭션 동기화 없음 → 즉시 정리)
 *  - 트랜잭션 안에서 예외가 난 경우(롤백 완료 시점에 정리)
 *  - 메서드는 끝났는데 커밋이 실패한 경우(롤백 완료 시점에 정리)
 *  - 커밋에 성공한 경우(정리하지 않음)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkspaceDuplicateRollbackTest {

    @TempDir
    Path temp;

    @Mock WorkspaceRepository workspaceRepository;
    @Mock ProjectRepository projectRepository;
    @Mock WorkspaceDataCloner workspaceDataCloner;

    @InjectMocks WorkspaceService workspaceService;

    private Workspace original;
    private Path originalRoot;
    private Path duplicateRoot;

    @BeforeEach
    void setUp() throws Exception {
        originalRoot = temp.resolve("원본");
        duplicateRoot = temp.resolve("사본");
        Files.createDirectories(originalRoot.resolve("app/master"));
        Files.writeString(originalRoot.resolve("app/master/Main.java"), "class Main {}");

        User owner = User.builder().id(7L).email("o@test").password("x").nickname("o").build();
        original = Workspace.createPersonal(owner, "원본", "", originalRoot.toString());

        when(workspaceRepository.findByUuid(original.getUuid())).thenReturn(Optional.of(original));
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));
        when(projectRepository.findByWorkspaceUuidOrderByUpdatedAtDesc(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("데이터 복제 중 예외(동기화 없음): 사본 폴더를 바로 지우고 원본은 남긴다")
    void failureWithoutSynchronization() {
        when(workspaceDataCloner.cloneAll(any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(this::duplicate).isInstanceOf(RuntimeException.class);

        assertThat(duplicateRoot).doesNotExist();
        assertThat(originalRoot.resolve("app/master/Main.java")).hasContent("class Main {}");
    }

    @Test
    @DisplayName("데이터 복제 중 예외(트랜잭션 안): 롤백이 끝나면 사본 폴더를 지운다")
    void failureInsideTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        when(workspaceDataCloner.cloneAll(any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(this::duplicate).isInstanceOf(RuntimeException.class);

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(duplicateRoot).doesNotExist();
        assertThat(originalRoot.resolve("app/master/Main.java")).hasContent("class Main {}");
    }

    @Test
    @DisplayName("메서드는 성공했지만 커밋이 실패: 사본 폴더를 지운다")
    void commitFailure() throws Exception {
        TransactionSynchronizationManager.initSynchronization();

        duplicate();
        assertThat(duplicateRoot.resolve("app/master/Main.java")).exists();

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(duplicateRoot).doesNotExist();
        assertThat(originalRoot.resolve("app/master/Main.java")).hasContent("class Main {}");
    }

    @Test
    @DisplayName("커밋 성공: 사본 폴더를 남긴다")
    void commitSuccessKeepsFolder() throws Exception {
        TransactionSynchronizationManager.initSynchronization();

        duplicate();
        complete(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(duplicateRoot.resolve("app/master/Main.java")).hasContent("class Main {}");
    }

    private void duplicate() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WorkspaceDuplicateRequest request = mapper.readValue(
                mapper.writeValueAsString(Map.of("name", "사본")), WorkspaceDuplicateRequest.class);

        workspaceService.duplicateWorkspace(original.getUuid(), 7L, request);
    }

    private static void complete(int status) {
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(sync -> sync.afterCompletion(status));
    }
}
