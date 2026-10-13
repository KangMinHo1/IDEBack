package com.myide.backend.service.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.ide.FileRequest;
import com.myide.backend.handler.CollaborationWebSocketHandler;
import com.myide.backend.handler.WorkspaceEventWebSocketHandler;
import com.myide.backend.service.CodeMapService;
import com.myide.backend.service.FileService;
import com.myide.backend.service.WorkspaceService;
import com.myide.backend.service.saferun.AuthorStatus;
import com.myide.backend.service.saferun.AuthorStatusService;
import com.myide.backend.service.saferun.BranchKey;
import com.myide.backend.service.saferun.EditLedger;
import com.myide.backend.service.saferun.SafeRunPaths;
import com.myide.backend.service.saferun.SyntaxChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 샌드박스(개인 레이어)의 한 번의 일생을 확인한다 — 켜기, 처음 고칠 때 사본 뜨기,
 * 반영(3-way 판정과 서버 직접 반영), 끄기, 버리기.
 *
 * 팀 파일에 쓰는 것은 진짜 FileService 를 통해 임시 폴더에 쓴다. 옛 방식 샌드박스
 * 브랜치(focus-u12-login) 위에서 돌려, 그 브랜치가 평범한 브랜치로 다뤄지는 것도 같이 본다.
 */
class SandboxServiceTest {

    private static final Long ME = 12L;
    private static final Long OTHER = 13L;
    private static final BranchKey KEY = BranchKey.of("ws-1", "shop", "focus-u12-login");
    private static final String ROOM = "ws-1:shop:focus-u12-login:src/Main.java";

    @TempDir
    Path temp;

    private Path live;
    private SafeRunPaths paths;
    private EditLedger ledger;
    private AuthorStatusService authorStatusService;
    private CollaborationWebSocketHandler collaborationHandler;
    private WorkspaceEventWebSocketHandler workspaceEventHandler;
    private SandboxService sandbox;

    @BeforeEach
    void setUp() throws IOException {
        live = temp.resolve("live");
        Files.createDirectories(live);

        WorkspaceService workspaceService =
                Mockito.mock(WorkspaceService.class, Mockito.CALLS_REAL_METHODS);
        Mockito.doReturn(live).when(workspaceService)
                .getProjectPath("ws-1", "shop", "focus-u12-login");

        paths = new SafeRunPaths(temp.resolve("safe-run").toString(), workspaceService);
        ledger = new EditLedger();
        authorStatusService = new AuthorStatusService(ledger, new SyntaxChecker(), key -> {});
        collaborationHandler = new CollaborationWebSocketHandler();
        workspaceEventHandler = Mockito.mock(WorkspaceEventWebSocketHandler.class);

        sandbox = new SandboxService(
                paths,
                new ThreeWayMerger(),
                new FileService(workspaceService, Mockito.mock(CodeMapService.class)),
                collaborationHandler,
                workspaceEventHandler,
                authorStatusService,
                new ObjectMapper()
        );
    }

    private void writeLive(String path, String content) throws IOException {
        Path file = live.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private String readLive(String path) throws IOException {
        return Files.readString(live.resolve(path));
    }

    private String broadcastAction() {
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        Mockito.verify(workspaceEventHandler)
                .broadcastFileTreeChanged(Mockito.any(FileRequest.class), action.capture());
        return action.getValue();
    }

    // ── 켜기 / 끄기 ───────────────────────────────────────────────────

    @Test
    @DisplayName("켜고 끄는 것은 깃발만 바꾸고, 브랜치 폴더를 만들지 않는다")
    void enableIsJustAFlag() throws IOException {
        assertThat(sandbox.state(KEY, ME).enabled()).isFalse();

        assertThat(sandbox.enable(KEY, ME).enabled()).isTrue();
        assertThat(sandbox.state(KEY, ME).enabled()).isTrue();

        // 옛 방식은 여기서 worktree 폴더가 하나 더 생겼다.
        try (var children = Files.list(live.getParent())) {
            assertThat(children.map(path -> path.getFileName().toString()))
                    .containsExactlyInAnyOrder("live", "safe-run");
        }
    }

    @Test
    @DisplayName("꺼도 고친 파일은 남아 있고, 다시 켜면 이어서 작업한다")
    void disableKeepsMyWork() throws IOException {
        writeLive("src/Main.java", "team");
        sandbox.enable(KEY, ME);
        sandbox.fork(KEY, ME, "src/Main.java", null);
        sandbox.saveFile(KEY, ME, "src/Main.java", "mine");

        SandboxService.State off = sandbox.disable(KEY, ME);

        assertThat(off.enabled()).isFalse();
        assertThat(off.files()).containsExactly(
                new SandboxService.PendingFile("src/Main.java", SandboxService.Kind.MODIFIED));

        SandboxService.State on = sandbox.enable(KEY, ME);

        assertThat(on.enabled()).isTrue();
        assertThat(sandbox.readFile(KEY, ME, "src/Main.java")).isEqualTo("mine");
    }

    @Test
    @DisplayName("상태 조회는 반영 안 된 파일을 수정·새 파일·삭제로 구분해 준다")
    void stateListsPendingFiles() throws IOException {
        writeLive("Edit.java", "team");
        writeLive("Gone.java", "team");

        sandbox.saveFile(KEY, ME, "Edit.java", "mine");
        sandbox.saveFile(KEY, ME, "Fresh.java", "new");
        sandbox.deleteFile(KEY, ME, "Gone.java");

        assertThat(sandbox.state(KEY, ME).files()).containsExactly(
                new SandboxService.PendingFile("Edit.java", SandboxService.Kind.MODIFIED),
                new SandboxService.PendingFile("Fresh.java", SandboxService.Kind.NEW),
                new SandboxService.PendingFile("Gone.java", SandboxService.Kind.DELETED));
    }

    @Test
    @DisplayName("샌드박스는 사람마다 따로다")
    void sandboxesAreSeparatePerUser() throws IOException {
        writeLive("src/Main.java", "team");
        sandbox.saveFile(KEY, ME, "src/Main.java", "mine");

        assertThat(sandbox.state(KEY, OTHER).files()).isEmpty();
        assertThat(sandbox.readFile(KEY, OTHER, "src/Main.java")).isEqualTo("team");
    }

    // ── 처음 고칠 때 사본 뜨기 ─────────────────────────────────────────

    @Test
    @DisplayName("처음 고칠 때 브라우저가 준 최신 내용으로 사본을 뜬다")
    void forkPrefersLiveContentFromBrowser() throws IOException {
        // 디스크는 최대 30초 늦다. 팀원이 방금 친 내용은 브라우저의 문서에만 있다.
        writeLive("src/Main.java", "stale disk");

        String content = sandbox.fork(KEY, ME, "src/Main.java", "fresh from the shared doc");

        assertThat(content).isEqualTo("fresh from the shared doc");
        assertThat(sandbox.readFile(KEY, ME, "src/Main.java")).isEqualTo("fresh from the shared doc");

        // base 도 같은 내용이어야 반영할 때 "팀원의 최근 입력"을 내 변경으로 착각하지 않는다.
        assertThat(sandbox.merge(KEY, ME, "src/Main.java", "fresh from the shared doc").status())
                .isEqualTo(SandboxService.MergeStatus.UNCHANGED);
    }

    @Test
    @DisplayName("브라우저가 내용을 주지 않으면 디스크를 쓴다")
    void forkFallsBackToDisk() throws IOException {
        writeLive("src/Main.java", "disk");

        assertThat(sandbox.fork(KEY, ME, "src/Main.java", null)).isEqualTo("disk");
    }

    @Test
    @DisplayName("사본은 한 번만 뜬다 — 다시 불러도 내 수정과 원본이 덮이지 않는다")
    void forkHappensOnce() throws IOException {
        writeLive("src/Main.java", "v1");
        sandbox.fork(KEY, ME, "src/Main.java", null);
        sandbox.saveFile(KEY, ME, "src/Main.java", "mine");

        // 그 사이 팀이 고쳤다.
        writeLive("src/Main.java", "v2 (team)");

        assertThat(sandbox.fork(KEY, ME, "src/Main.java", "v2 (team)")).isEqualTo("mine");

        // base 가 v1 로 남아 있어야 한다. v2 로 바뀌면 반영할 때 팀의 변경을 내 것으로 덮는다.
        SandboxService.MergeView view = sandbox.merge(KEY, ME, "src/Main.java", null);
        assertThat(view.status()).isEqualTo(SandboxService.MergeStatus.CONFLICT);
    }

    @Test
    @DisplayName("샌드박스에서 고쳐도 팀 파일은 그대로다")
    void savingNeverTouchesLive() throws IOException {
        writeLive("src/Main.java", "team");

        sandbox.saveFile(KEY, ME, "src/Main.java", "mine");
        sandbox.deleteFile(KEY, ME, "src/Main.java");

        assertThat(readLive("src/Main.java")).isEqualTo("team");
    }

    @Test
    @DisplayName("고치지 않은 파일은 팀 파일을 그대로 읽는다")
    void untouchedFilesReadThrough() throws IOException {
        writeLive("Other.java", "team v1");
        assertThat(sandbox.readFile(KEY, ME, "Other.java")).isEqualTo("team v1");

        // 사본이 없으므로 팀이 고치면 바로 따라간다.
        writeLive("Other.java", "team v2");
        assertThat(sandbox.readFile(KEY, ME, "Other.java")).isEqualTo("team v2");
    }

    // ── 반영 판정 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("팀 파일이 그대로면 내 것으로 깨끗하게 반영된다")
    void mergeCleanWhenLiveUntouched() throws IOException {
        writeLive("src/Main.java", "v1");
        sandbox.saveFile(KEY, ME, "src/Main.java", "mine");

        SandboxService.MergeView view = sandbox.merge(KEY, ME, "src/Main.java", null);

        assertThat(view.status()).isEqualTo(SandboxService.MergeStatus.CLEAN);
        assertThat(view.content()).isEqualTo("mine");
        assertThat(view.roomOpen()).isFalse();
    }

    @Test
    @DisplayName("반영 판정은 브라우저가 준 최신 내용을 팀 파일로 본다")
    void mergeUsesLiveContentFromBrowser() throws IOException {
        writeLive("src/Main.java", "a\nb\nc\n");
        sandbox.saveFile(KEY, ME, "src/Main.java", "a (mine)\nb\nc\n");

        SandboxService.MergeView view =
                sandbox.merge(KEY, ME, "src/Main.java", "a\nb\nc (team, not yet on disk)\n");

        assertThat(view.status()).isEqualTo(SandboxService.MergeStatus.CLEAN);
        assertThat(view.content()).isEqualTo("a (mine)\nb\nc (team, not yet on disk)\n");
    }

    @Test
    @DisplayName("팀 쪽에 없는 파일은 새 파일로 판정한다")
    void mergeNewFile() {
        sandbox.saveFile(KEY, ME, "Fresh.java", "new");

        SandboxService.MergeView view = sandbox.merge(KEY, ME, "Fresh.java", null);

        assertThat(view.status()).isEqualTo(SandboxService.MergeStatus.NEW_FILE);
        assertThat(view.content()).isEqualTo("new");
    }

    @Test
    @DisplayName("내가 지운 파일은 삭제로, 그 사이 팀이 고쳤으면 삭제 충돌로 판정한다")
    void mergeDeletion() throws IOException {
        writeLive("Gone.java", "v1");
        sandbox.deleteFile(KEY, ME, "Gone.java");

        assertThat(sandbox.merge(KEY, ME, "Gone.java", null).status())
                .isEqualTo(SandboxService.MergeStatus.DELETED);

        writeLive("Gone.java", "v2 (team)");

        SandboxService.MergeView conflict = sandbox.merge(KEY, ME, "Gone.java", null);
        assertThat(conflict.status()).isEqualTo(SandboxService.MergeStatus.DELETE_CONFLICT);
        assertThat(conflict.content()).isEqualTo("v2 (team)");
    }

    @Test
    @DisplayName("방이 열려 있으면 판정 결과에 그렇게 알려 준다")
    void mergeReportsOpenRoom() throws IOException {
        writeLive("src/Main.java", "v1");
        sandbox.saveFile(KEY, ME, "src/Main.java", "mine");
        collaborationHandler.seedDoc(ROOM, "yjs-doc");

        assertThat(sandbox.merge(KEY, ME, "src/Main.java", null).roomOpen()).isTrue();
    }

    @Test
    @DisplayName("샌드박스에 없는 파일은 반영할 수 없다")
    void mergeUnknownFile() {
        assertThatThrownBy(() -> sandbox.merge(KEY, ME, "Nope.java", null))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ── 서버 직접 반영 ────────────────────────────────────────────────

    @Test
    @DisplayName("방이 닫힌 파일은 서버가 디스크에 쓰고, 샌드박스에서 빼고, 팀에 알린다")
    void applyDirectWritesClosedRoomFile() throws IOException {
        writeLive("src/Main.java", "class Main {}");
        sandbox.saveFile(KEY, ME, "src/Main.java", "class Main { int x; }");

        SandboxService.MergeView view = sandbox.applyDirect(KEY, ME, "src/Main.java");

        assertThat(view.status()).isEqualTo(SandboxService.MergeStatus.CLEAN);
        assertThat(readLive("src/Main.java")).isEqualTo("class Main { int x; }");
        assertThat(sandbox.state(KEY, ME).files()).isEmpty();
        assertThat(broadcastAction()).isEqualTo("UPDATE");

        // 반영한 사람의 변경으로 장부에 적힌다. 실행에서 이 사람을 제외하면 이 파일이 빠진다.
        assertThat(ledger.authorsOf(KEY, "src/Main.java")).containsExactly(ME);
        assertThat(authorStatusService.statuses(KEY)).containsEntry(ME, AuthorStatus.READY);
    }

    @Test
    @DisplayName("방이 열린 파일은 서버가 쓰지 않고 거절한다")
    void applyDirectRefusesOpenRoom() throws IOException {
        writeLive("src/Main.java", "v1");
        sandbox.saveFile(KEY, ME, "src/Main.java", "mine");
        collaborationHandler.seedDoc(ROOM, "yjs-doc");

        // 디스크만 고치면 방의 문서는 옛 내용이라, 자동 저장이 곧 내 반영을 덮는다.
        assertThatThrownBy(() -> sandbox.applyDirect(KEY, ME, "src/Main.java"))
                .isInstanceOf(SandboxService.RoomOpenException.class);

        assertThat(readLive("src/Main.java")).isEqualTo("v1");
        assertThat(sandbox.readFile(KEY, ME, "src/Main.java")).isEqualTo("mine");
        Mockito.verifyNoInteractions(workspaceEventHandler);
    }

    @Test
    @DisplayName("충돌이면 아무것도 쓰지 않고 충돌 본문을 돌려준다")
    void applyDirectStopsOnConflict() throws IOException {
        writeLive("src/Main.java", "v1\n");
        sandbox.saveFile(KEY, ME, "src/Main.java", "mine\n");
        writeLive("src/Main.java", "team\n");

        SandboxService.MergeView view = sandbox.applyDirect(KEY, ME, "src/Main.java");

        assertThat(view.status()).isEqualTo(SandboxService.MergeStatus.CONFLICT);
        assertThat(view.content()).contains("<<<<<<< sandbox", "mine", "team");
        assertThat(readLive("src/Main.java")).isEqualTo("team\n");
        assertThat(sandbox.readFile(KEY, ME, "src/Main.java")).isEqualTo("mine\n");
    }

    @Test
    @DisplayName("충돌을 해결하면 다시 반영할 때 같은 자리에서 또 충돌하지 않는다")
    void resolvedConflictAppliesCleanly() throws IOException {
        writeLive("src/Main.java", "v1\n");
        sandbox.saveFile(KEY, ME, "src/Main.java", "mine\n");
        writeLive("src/Main.java", "team\n");

        SandboxService.MergeView conflict = sandbox.merge(KEY, ME, "src/Main.java", "team\n");
        assertThat(conflict.status()).isEqualTo(SandboxService.MergeStatus.CONFLICT);

        // 사용자가 "둘 다"를 골랐다. 기준을 옮기지 않으면 여기서 또 충돌이 난다.
        sandbox.resolve(KEY, ME, "src/Main.java", "mine\nteam\n", "team\n");

        SandboxService.MergeView again = sandbox.merge(KEY, ME, "src/Main.java", "team\n");
        assertThat(again.status()).isEqualTo(SandboxService.MergeStatus.CLEAN);
        assertThat(again.content()).isEqualTo("mine\nteam\n");
    }

    @Test
    @DisplayName("충돌을 해결하는 동안 팀이 또 고쳤으면 새 기준으로 다시 합친다")
    void resolvedConflictStillMergesLaterTeamEdits() throws IOException {
        writeLive("src/Main.java", "a\nb\nc\n");
        sandbox.saveFile(KEY, ME, "src/Main.java", "a (mine)\nb\nc\n");

        sandbox.resolve(KEY, ME, "src/Main.java", "a (resolved)\nb\nc\n", "a (team)\nb\nc\n");

        SandboxService.MergeView view =
                sandbox.merge(KEY, ME, "src/Main.java", "a (team)\nb\nc (team again)\n");

        assertThat(view.status()).isEqualTo(SandboxService.MergeStatus.CLEAN);
        assertThat(view.content()).isEqualTo("a (resolved)\nb\nc (team again)\n");
    }

    @Test
    @DisplayName("새 파일은 서버가 만들고 파일 트리 변경(CREATE)을 알린다")
    void applyDirectCreatesNewFile() throws IOException {
        sandbox.saveFile(KEY, ME, "pkg/Fresh.java", "class Fresh {}");

        SandboxService.MergeView view = sandbox.applyDirect(KEY, ME, "pkg/Fresh.java");

        assertThat(view.status()).isEqualTo(SandboxService.MergeStatus.NEW_FILE);
        assertThat(readLive("pkg/Fresh.java")).isEqualTo("class Fresh {}");
        assertThat(broadcastAction()).isEqualTo("CREATE");
        assertThat(sandbox.state(KEY, ME).files()).isEmpty();
    }

    @Test
    @DisplayName("삭제는 방이 열려 있어도 서버가 처리하고, 보관 문서를 버리고, DELETE 를 알린다")
    void applyDirectDeletesEvenWhenRoomIsOpen() throws IOException {
        writeLive("src/Main.java", "v1");
        sandbox.deleteFile(KEY, ME, "src/Main.java");
        collaborationHandler.seedDoc(ROOM, "yjs-doc");

        SandboxService.MergeView view = sandbox.applyDirect(KEY, ME, "src/Main.java");

        assertThat(view.status()).isEqualTo(SandboxService.MergeStatus.DELETED);
        assertThat(Files.exists(live.resolve("src/Main.java"))).isFalse();
        assertThat(broadcastAction()).isEqualTo("DELETE");

        // 같은 이름으로 다시 만든 파일이 지워진 파일의 옛 문서를 받으면 안 된다.
        assertThat(collaborationHandler.getDoc(ROOM)).isNull();
        assertThat(sandbox.state(KEY, ME).files()).isEmpty();
    }

    @Test
    @DisplayName("반영할 것이 없는 파일은 샌드박스에서 빼기만 한다")
    void applyDirectDropsUnchangedFile() throws IOException {
        writeLive("src/Main.java", "v1");
        sandbox.fork(KEY, ME, "src/Main.java", null);

        SandboxService.MergeView view = sandbox.applyDirect(KEY, ME, "src/Main.java");

        assertThat(view.status()).isEqualTo(SandboxService.MergeStatus.UNCHANGED);
        assertThat(sandbox.state(KEY, ME).files()).isEmpty();
        Mockito.verifyNoInteractions(workspaceEventHandler);
    }

    @Test
    @DisplayName("브라우저가 반영을 마쳤다고 알리면 그 파일만 샌드박스에서 빠진다")
    void markAppliedRemovesOnlyThatFile() throws IOException {
        writeLive("A.java", "a");
        writeLive("B.java", "b");
        sandbox.saveFile(KEY, ME, "A.java", "a mine");
        sandbox.saveFile(KEY, ME, "B.java", "b mine");

        sandbox.markApplied(KEY, ME, "A.java");

        assertThat(sandbox.state(KEY, ME).files()).containsExactly(
                new SandboxService.PendingFile("B.java", SandboxService.Kind.MODIFIED));
    }

    // ── 팀 변경 알림 / 가져오기 ───────────────────────────────────────

    @Test
    @DisplayName("내가 고치는 동안 팀이 그 파일을 고치면 상태에 표시된다")
    void stateFlagsFilesTheTeamChanged() throws IOException {
        writeLive("Same.java", "v1");
        writeLive("Moved.java", "v1");
        sandbox.saveFile(KEY, ME, "Same.java", "mine");
        sandbox.saveFile(KEY, ME, "Moved.java", "mine");

        // 샌드박스에서 고치는 파일은 팀원의 편집이 화면에 안 보인다. 이 표시가 유일한 단서다.
        writeLive("Moved.java", "v2 (team)");

        assertThat(sandbox.state(KEY, ME).files()).containsExactly(
                new SandboxService.PendingFile("Moved.java", SandboxService.Kind.MODIFIED, true),
                new SandboxService.PendingFile("Same.java", SandboxService.Kind.MODIFIED, false));
    }

    @Test
    @DisplayName("내가 지운 파일을 팀이 고쳤거나, 내 새 파일과 같은 이름을 팀이 만들어도 표시된다")
    void stateFlagsDeletedAndNewFiles() throws IOException {
        writeLive("Gone.java", "v1");
        sandbox.deleteFile(KEY, ME, "Gone.java");
        sandbox.saveFile(KEY, ME, "Fresh.java", "mine");

        writeLive("Gone.java", "v2 (team)");
        writeLive("Fresh.java", "team made it too");

        assertThat(sandbox.state(KEY, ME).files()).containsExactly(
                new SandboxService.PendingFile("Fresh.java", SandboxService.Kind.NEW, true),
                new SandboxService.PendingFile("Gone.java", SandboxService.Kind.DELETED, true));
    }

    @Test
    @DisplayName("서로 다른 곳을 고쳤으면 팀 변경이 내 것에 합쳐지고, 팀 파일은 그대로다")
    void pullMergesTeamChangesIntoMine() throws IOException {
        writeLive("src/Main.java", "a\nb\nc\n");
        sandbox.saveFile(KEY, ME, "src/Main.java", "a (mine)\nb\nc\n");
        writeLive("src/Main.java", "a\nb\nc (team)\n");

        SandboxService.MergeView view =
                sandbox.pull(KEY, ME, "src/Main.java", "a (mine)\nb\nc\n", "a\nb\nc (team)\n");

        assertThat(view.status()).isEqualTo(SandboxService.MergeStatus.CLEAN);
        assertThat(view.content()).isEqualTo("a (mine)\nb\nc (team)\n");
        assertThat(sandbox.readFile(KEY, ME, "src/Main.java")).isEqualTo("a (mine)\nb\nc (team)\n");

        // 가져오기는 팀 → 나 한 방향이다. 내 수정이 팀에 가는 것은 반영할 때뿐이다.
        assertThat(readLive("src/Main.java")).isEqualTo("a\nb\nc (team)\n");
        Mockito.verifyNoInteractions(workspaceEventHandler);
    }

    @Test
    @DisplayName("가져온 뒤에는 팀 변경 표시가 꺼지고, 바로 반영하면 충돌 없이 들어간다")
    void pullMovesTheBase() throws IOException {
        writeLive("src/Main.java", "a\nb\nc\n");
        sandbox.saveFile(KEY, ME, "src/Main.java", "a (mine)\nb\nc\n");
        writeLive("src/Main.java", "a\nb\nc (team)\n");

        sandbox.pull(KEY, ME, "src/Main.java", "a (mine)\nb\nc\n", null);

        // 기준을 옮기지 않으면 같은 변경을 계속 "팀이 고쳤다"로 본다.
        assertThat(sandbox.state(KEY, ME).files()).containsExactly(
                new SandboxService.PendingFile("src/Main.java", SandboxService.Kind.MODIFIED, false));

        SandboxService.MergeView apply = sandbox.merge(KEY, ME, "src/Main.java", null);
        assertThat(apply.status()).isEqualTo(SandboxService.MergeStatus.CLEAN);
        assertThat(apply.content()).isEqualTo("a (mine)\nb\nc (team)\n");

        // 한 번 더 가져와도 달라지는 것이 없다.
        assertThat(sandbox.pull(KEY, ME, "src/Main.java", "a (mine)\nb\nc (team)\n", null).status())
                .isEqualTo(SandboxService.MergeStatus.UNCHANGED);
    }

    @Test
    @DisplayName("브라우저가 준 내 문서 내용이 서버에 저장된 것보다 앞서 있으면 그것으로 합친다")
    void pullUsesMineFromBrowser() throws IOException {
        writeLive("src/Main.java", "a\nb\nc\n");
        sandbox.saveFile(KEY, ME, "src/Main.java", "a (saved)\nb\nc\n");

        // 자동 저장은 몇 초 늦다. 방금 친 것은 브라우저의 문서에만 있다.
        SandboxService.MergeView view =
                sandbox.pull(KEY, ME, "src/Main.java", "a (just typed)\nb\nc\n", "a\nb\nc (team)\n");

        assertThat(view.content()).isEqualTo("a (just typed)\nb\nc (team)\n");
    }

    @Test
    @DisplayName("나는 사본만 뜨고 안 고쳤으면 팀 것으로 그대로 바뀐다")
    void pullReplacesUntouchedCopy() throws IOException {
        writeLive("src/Main.java", "v1");
        sandbox.fork(KEY, ME, "src/Main.java", null);

        SandboxService.MergeView view = sandbox.pull(KEY, ME, "src/Main.java", "v1", "v2 (team)");

        assertThat(view.status()).isEqualTo(SandboxService.MergeStatus.CLEAN);
        assertThat(view.content()).isEqualTo("v2 (team)");
    }

    @Test
    @DisplayName("같은 곳을 고쳤으면 아무것도 쓰지 않고 충돌을 돌려준다")
    void pullStopsOnConflict() throws IOException {
        writeLive("src/Main.java", "v1\n");
        sandbox.saveFile(KEY, ME, "src/Main.java", "mine\n");

        SandboxService.MergeView view = sandbox.pull(KEY, ME, "src/Main.java", "mine\n", "team\n");

        assertThat(view.status()).isEqualTo(SandboxService.MergeStatus.CONFLICT);
        assertThat(view.content()).contains("<<<<<<< sandbox", "mine", "team");

        // 내 파일과 기준은 그대로다. 고른 뒤 resolve 가 옮긴다.
        assertThat(sandbox.readFile(KEY, ME, "src/Main.java")).isEqualTo("mine\n");

        sandbox.resolve(KEY, ME, "src/Main.java", "mine\nteam\n", "team\n");
        assertThat(sandbox.merge(KEY, ME, "src/Main.java", "team\n").status())
                .isEqualTo(SandboxService.MergeStatus.CLEAN);
    }

    @Test
    @DisplayName("팀이 안 고쳤으면 가져올 것이 없다")
    void pullNothingWhenTeamUntouched() throws IOException {
        writeLive("src/Main.java", "v1");
        sandbox.saveFile(KEY, ME, "src/Main.java", "mine");

        SandboxService.MergeView view = sandbox.pull(KEY, ME, "src/Main.java", "mine", "v1");

        assertThat(view.status()).isEqualTo(SandboxService.MergeStatus.UNCHANGED);
        assertThat(view.content()).isEqualTo("mine");
    }

    @Test
    @DisplayName("팀 파일이 바뀌면, 그 파일을 샌드박스에 둔 사람이 있을 때만 알린다")
    void notifiesOnlyWhenSomeoneForkedTheFile() throws IOException {
        writeLive("src/Main.java", "v1");
        writeLive("Other.java", "v1");
        sandbox.saveFile(KEY, ME, "src/Main.java", "mine");

        assertThat(sandbox.hasForkOf(KEY, "src/Main.java")).isTrue();
        assertThat(sandbox.hasForkOf(KEY, "src")).as("폴더가 지워지는 경우").isTrue();
        assertThat(sandbox.hasForkOf(KEY, "Other.java")).isFalse();

        // 평소 저장마다 알림이 나가면 안 된다.
        sandbox.notifyTeamFileChanged(KEY, "Other.java");
        Mockito.verifyNoInteractions(workspaceEventHandler);

        sandbox.notifyTeamFileChanged(KEY, "src/Main.java");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(workspaceEventHandler).broadcastToBranch(
                Mockito.eq("ws-1"), Mockito.eq("shop"), Mockito.eq("focus-u12-login"), payload.capture());

        assertThat(payload.getValue()).isEqualTo(java.util.Map.of(
                "type", "SANDBOX_TEAM_FILE_CHANGED",
                "workspaceId", "ws-1",
                "projectName", "shop",
                "branchName", "focus-u12-login",
                "filePath", "src/Main.java"));
    }

    @Test
    @DisplayName("내가 반영해 팀 파일이 바뀌면, 같은 파일을 샌드박스에서 고치던 다른 사람에게 알린다")
    void applyNotifiesOtherSandboxes() throws IOException {
        writeLive("src/Main.java", "a\nb\nc\n");
        sandbox.saveFile(KEY, ME, "src/Main.java", "a (mine)\nb\nc\n");
        sandbox.saveFile(KEY, OTHER, "src/Main.java", "a\nb\nc (other)\n");

        sandbox.applyDirect(KEY, ME, "src/Main.java");

        Mockito.verify(workspaceEventHandler).broadcastToBranch(
                Mockito.eq("ws-1"), Mockito.eq("shop"), Mockito.eq("focus-u12-login"), Mockito.any());

        assertThat(sandbox.state(KEY, OTHER).files()).containsExactly(
                new SandboxService.PendingFile("src/Main.java", SandboxService.Kind.MODIFIED, true));
    }

    // ── 버리기 / 실행 ─────────────────────────────────────────────────

    @Test
    @DisplayName("버리면 내 샌드박스 수정이 모두 사라지고 팀 파일은 그대로다")
    void discardRemovesEverything() throws IOException {
        writeLive("src/Main.java", "team");
        sandbox.enable(KEY, ME);
        sandbox.saveFile(KEY, ME, "src/Main.java", "mine");
        sandbox.saveFile(KEY, ME, "Fresh.java", "new");

        sandbox.discard(KEY, ME);

        assertThat(sandbox.state(KEY, ME)).isEqualTo(new SandboxService.State(false, java.util.List.of()));
        assertThat(Files.exists(paths.sandbox(KEY, ME))).isFalse();
        assertThat(sandbox.readFile(KEY, ME, "src/Main.java")).isEqualTo("team");
        assertThat(sandbox.overlay(KEY, ME)).isNull();
    }

    @Test
    @DisplayName("실행에 덮을 정보는 내 파일 폴더와 내가 지운 목록이다")
    void overlayForRun() throws IOException {
        writeLive("Gone.java", "team");
        sandbox.saveFile(KEY, ME, "Fresh.java", "new");
        sandbox.deleteFile(KEY, ME, "Gone.java");

        SandboxService.Overlay overlay = sandbox.overlay(KEY, ME);

        assertThat(Files.readString(overlay.files().resolve("Fresh.java"))).isEqualTo("new");
        assertThat(overlay.deleted()).containsExactly("Gone.java");
    }

    @Test
    @DisplayName("경로로 샌드박스 폴더 밖을 건드릴 수 없다")
    void refusesEscapingPaths() {
        assertThatThrownBy(() -> sandbox.saveFile(KEY, ME, "../../../evil.txt", "x"))
                .isInstanceOf(SecurityException.class);
    }
}
