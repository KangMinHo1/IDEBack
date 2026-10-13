package com.myide.backend.service.saferun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 준비됨/수정 중 판정과 "바뀔 때만 알린다"를 확인한다.
 *
 * 알림이 너무 잦으면 글자를 칠 때마다 팀 전체 화면이 다시 그려지고, 빠지면 상태바가
 * 실제와 어긋난다.
 */
class AuthorStatusServiceTest {

    private static final BranchKey KEY = BranchKey.of("ws-1", "shop", "develop");

    private static final String GOOD = "class A { void run() { } }";
    private static final String BROKEN = "class A { void run() { ";

    private EditLedger ledger;
    private List<BranchKey> notifications;
    private AuthorStatusService service;

    @BeforeEach
    void setUp() {
        ledger = new EditLedger();
        notifications = new ArrayList<>();
        service = new AuthorStatusService(ledger, new SyntaxChecker(), notifications::add);
    }

    @Test
    @DisplayName("고치기 시작한 사람은 준비됨으로 나타난다")
    void newAuthorStartsReady() {
        service.onEdit("ws-1", "shop", "develop", "A.java", 1L);

        assertThat(service.statuses(KEY)).containsEntry(1L, AuthorStatus.READY);
        assertThat(notifications).hasSize(1);
    }

    @Test
    @DisplayName("깨진 내용이 저장되면 그 파일의 작성자가 수정 중이 된다")
    void brokenSaveMakesAuthorEditing() {
        service.recordEdit(KEY, "A.java", 1L);

        service.onFileSaved(KEY, "A.java", BROKEN);

        assertThat(service.statuses(KEY)).containsEntry(1L, AuthorStatus.EDITING);
    }

    @Test
    @DisplayName("고친 파일이 전부 통과해야 준비됨이다")
    void needsEveryFileToPass() {
        service.recordEdit(KEY, "A.java", 1L);
        service.recordEdit(KEY, "B.java", 1L);

        service.onFileSaved(KEY, "A.java", GOOD);
        service.onFileSaved(KEY, "B.java", BROKEN);
        assertThat(service.statuses(KEY)).containsEntry(1L, AuthorStatus.EDITING);

        service.onFileSaved(KEY, "B.java", GOOD);
        assertThat(service.statuses(KEY)).containsEntry(1L, AuthorStatus.READY);
    }

    @Test
    @DisplayName("같은 파일을 함께 고친 사람은 함께 수정 중이 된다")
    void coAuthorsShareTheFileStatus() {
        service.recordEdit(KEY, "A.java", 1L);
        service.recordEdit(KEY, "A.java", 2L);
        service.recordEdit(KEY, "B.java", 3L);

        service.onFileSaved(KEY, "A.java", BROKEN);

        // 파일을 줄 단위로 가르지 않으므로, 누가 깨뜨렸는지는 구분하지 않는다.
        assertThat(service.statuses(KEY))
                .containsEntry(1L, AuthorStatus.EDITING)
                .containsEntry(2L, AuthorStatus.EDITING)
                .containsEntry(3L, AuthorStatus.READY);
    }

    @Test
    @DisplayName("상태가 그대로면 알리지 않는다")
    void notifiesOnlyOnChange() {
        service.recordEdit(KEY, "A.java", 1L);
        notifications.clear();

        // 같은 사람이 계속 치는 것, 통과하는 내용이 다시 저장되는 것은 변화가 아니다.
        service.recordEdit(KEY, "A.java", 1L);
        service.onFileSaved(KEY, "A.java", GOOD);
        service.onFileSaved(KEY, "A.java", GOOD);
        assertThat(notifications).isEmpty();

        service.onFileSaved(KEY, "A.java", BROKEN);
        assertThat(notifications).hasSize(1);

        service.onFileSaved(KEY, "A.java", BROKEN);
        assertThat(notifications).hasSize(1);

        service.onFileSaved(KEY, "A.java", GOOD);
        assertThat(notifications).hasSize(2);
    }

    @Test
    @DisplayName("장부에 없는 파일의 저장은 검사하지 않는다")
    void ignoresFilesOutsideTheLedger() {
        service.onFileSaved(KEY, "Unknown.java", BROKEN);

        assertThat(service.statuses(KEY)).isEmpty();
        assertThat(notifications).isEmpty();
    }

    @Test
    @DisplayName("깨진 파일을 지우면 준비됨으로 돌아온다")
    void removingBrokenFileRestoresReady() {
        service.recordEdit(KEY, "A.java", 1L);
        service.onFileSaved(KEY, "A.java", BROKEN);

        service.onFileRemoved(KEY, "A.java");

        assertThat(service.statuses(KEY)).containsEntry(1L, AuthorStatus.READY);
    }

    @Test
    @DisplayName("장부에서 빠진 사람은 목록에서도 빠지고, 그것도 알린다")
    void authorLeavesWhenLedgerShrinks() {
        service.recordEdit(KEY, "A.java", 1L);
        service.onFileSaved(KEY, "A.java", BROKEN);
        notifications.clear();

        // green 이 갱신되면 장부가 이렇게 줄어든다.
        ledger.removeIf(KEY, path -> true);
        service.refresh(KEY);

        assertThat(service.statuses(KEY)).isEmpty();
        assertThat(notifications).hasSize(1);

        // 같은 경로를 다시 고치기 시작했을 때 예전의 "깨짐"이 남아 있으면 안 된다.
        service.recordEdit(KEY, "A.java", 1L);
        assertThat(service.statuses(KEY)).containsEntry(1L, AuthorStatus.READY);
    }

    @Test
    @DisplayName("알림이 실패해도 예외를 밖으로 내지 않는다")
    void swallowsListenerFailure() {
        AuthorStatusService failing = new AuthorStatusService(ledger, new SyntaxChecker(), key -> {
            throw new IllegalStateException("소켓 끊김");
        });

        // 이 호출은 동시 편집 중계와 파일 저장 한가운데서 일어난다.
        failing.recordEdit(KEY, "A.java", 1L);

        assertThat(failing.statuses(KEY)).containsEntry(1L, AuthorStatus.READY);
    }
}
