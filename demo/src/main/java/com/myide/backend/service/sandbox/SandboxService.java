// 경로: src/main/java/com/myide/backend/service/sandbox/SandboxService.java
package com.myide.backend.service.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.ide.FileRequest;
import com.myide.backend.handler.CollaborationWebSocketHandler;
import com.myide.backend.handler.WorkspaceEventWebSocketHandler;
import com.myide.backend.security.CollabRoomName;
import com.myide.backend.service.FileService;
import com.myide.backend.service.saferun.AuthorStatusService;
import com.myide.backend.service.saferun.BranchKey;
import com.myide.backend.service.saferun.EditLedger;
import com.myide.backend.service.saferun.RunAssembler;
import com.myide.backend.service.saferun.SafeRunPaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 샌드박스 — 브랜치를 만들지 않고 켜는 "개인 레이어".
 *
 * 예전 샌드박스는 개인 브랜치(focus-u{회원번호}-…)를 떼어 내는 방식이라 일반 브랜치와
 * 다를 것이 없었다. 지금은 같은 브랜치에 머문 채, <b>내가 고친 파일만</b> 따로 보관한다.
 *
 * <pre>
 *   {sandbox}/files/   내가 고친 파일
 *   {sandbox}/base/    그 파일을 고치기 시작한 순간의 팀 파일 (반영할 때 3-way 비교용)
 *   {sandbox}/meta.json  켜짐 여부, 내가 지운 파일 목록
 * </pre>
 *
 * 고치지 않은 파일은 여기에 없다. 그래서 그 파일들은 계속 팀과 실시간으로 같이 본다.
 * 파일을 처음 고치는 순간에만 사본을 뜬다(Copy-on-Write).
 *
 * 팀 파일(live)에 쓰는 일은 반영할 때뿐이고, 그때도 FileService 를 거친다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxService {

    public enum Kind {
        MODIFIED,
        NEW,
        DELETED
    }

    public record PendingFile(String filePath, Kind kind) {}

    /** @param files 아직 팀 파일에 반영하지 않은 것 */
    public record State(boolean enabled, List<PendingFile> files) {}

    public enum MergeStatus {
        /** 반영할 것이 없다(내가 바꾸지 않았거나, 팀 파일이 이미 같은 내용이다). */
        UNCHANGED,
        /** 충돌 없이 합쳐진다. content 가 팀 파일에 넣을 내용이다. */
        CLEAN,
        /** 같은 곳을 팀도 고쳤다. content 는 충돌 표식이 든 본문이다. */
        CONFLICT,
        /** 팀 쪽에 없는 파일이다. 반영하면 새로 생긴다. */
        NEW_FILE,
        /** 내가 지운 파일이다. 반영하면 팀 쪽에서도 지워진다. */
        DELETED,
        /** 내가 지운 파일을 그 사이 팀이 고쳤다. content 는 팀의 지금 내용이다. */
        DELETE_CONFLICT
    }

    /** @param roomOpen 이 파일의 팀 공유 방이 열려 있는지. 열려 있으면 브라우저가 문서에 직접 넣어야 한다 */
    public record MergeView(String filePath, MergeStatus status, String content, boolean roomOpen) {}

    /** 내 샌드박스를 실행 폴더 위에 덮을 때 쓰는 정보. */
    public record Overlay(Path files, Set<String> deleted) {}

    /** meta.json 의 모양. */
    public record Meta(boolean enabled, List<String> deleted) {}

    private final SafeRunPaths paths;
    private final ThreeWayMerger merger;
    private final FileService fileService;
    private final CollaborationWebSocketHandler collaborationHandler;
    private final WorkspaceEventWebSocketHandler workspaceEventHandler;
    private final AuthorStatusService authorStatusService;
    private final ObjectMapper objectMapper;

    // ── 켜기 / 끄기 / 상태 ─────────────────────────────────────────────

    public synchronized State enable(BranchKey key, Long userId) {
        writeMeta(key, userId, new Meta(true, readMeta(key, userId).deleted()));
        return state(key, userId);
    }

    /**
     * 끈다. <b>고친 파일은 지우지 않는다.</b> 다시 켜면 이어서 작업한다.
     * 지우는 것은 discard 뿐이다.
     */
    public synchronized State disable(BranchKey key, Long userId) {
        if (Files.isDirectory(paths.sandbox(key, userId))) {
            writeMeta(key, userId, new Meta(false, readMeta(key, userId).deleted()));
        }

        return state(key, userId);
    }

    public synchronized State state(BranchKey key, Long userId) {
        Meta meta = readMeta(key, userId);
        Path files = filesDir(key, userId);
        Path base = baseDir(key, userId);
        List<PendingFile> pending = new ArrayList<>();

        if (Files.isDirectory(files)) {
            try (Stream<Path> walk = Files.walk(files)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    String path = files.relativize(file).toString().replace("\\", "/");
                    boolean hasBase = Files.isRegularFile(base.resolve(path));

                    pending.add(new PendingFile(path, hasBase ? Kind.MODIFIED : Kind.NEW));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        for (String deleted : meta.deleted()) {
            pending.add(new PendingFile(deleted, Kind.DELETED));
        }

        pending.sort(Comparator.comparing(PendingFile::filePath));
        return new State(meta.enabled(), pending);
    }

    // ── 파일 ───────────────────────────────────────────────────────────

    /**
     * 파일을 처음 고치는 순간 부른다(Copy-on-Write). 그 순간의 팀 파일을 base 와 files 에
     * 복사하고, 이후 이 파일의 편집은 내 샌드박스에서만 일어난다.
     *
     * @param liveContent 브라우저가 팀 공유 문서에서 읽은 지금 내용. 디스크는 최대 30초
     *                    늦으므로, 디스크만 보면 팀원이 방금 친 내용이 샌드박스에서 빠진다.
     *                    없을 때만(null) 디스크를 읽는다
     * @return 샌드박스 파일의 내용. 이미 사본이 있으면 그것을 그대로 돌려준다
     */
    public synchronized String fork(BranchKey key, Long userId, String filePath, String liveContent) {
        String path = requirePath(filePath);
        Path mine = inside(filesDir(key, userId), path);

        if (Files.isRegularFile(mine)) {
            return read(mine);
        }

        String content = liveContent != null ? liveContent : readLive(key, path);

        // 지웠던 파일을 다시 고치기 시작했다면 삭제 표시를 푼다.
        Meta meta = readMeta(key, userId);
        if (meta.deleted().contains(path)) {
            List<String> deleted = new ArrayList<>(meta.deleted());
            deleted.remove(path);
            writeMeta(key, userId, new Meta(meta.enabled(), deleted));
        }

        if (content == null) {
            // 팀 쪽에 없는 파일이다. 비교할 원본이 없으므로 base 를 만들지 않는다.
            write(mine, "");
            return "";
        }

        Path base = inside(baseDir(key, userId), path);
        if (!Files.isRegularFile(base)) {
            write(base, content);
        }

        write(mine, content);
        return content;
    }

    /** 샌드박스에서 본 파일 내용. 내가 고친 파일이면 내 것, 아니면 팀 파일. */
    public synchronized String readFile(BranchKey key, Long userId, String filePath) {
        String path = requirePath(filePath);

        if (readMeta(key, userId).deleted().contains(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "샌드박스에서 삭제한 파일입니다: " + path);
        }

        Path mine = inside(filesDir(key, userId), path);
        if (Files.isRegularFile(mine)) {
            return read(mine);
        }

        String live = readLive(key, path);
        if (live == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다: " + path);
        }

        return live;
    }

    /** 샌드박스 파일을 저장한다. 아직 사본이 없으면 디스크 기준으로 먼저 뜬다. */
    public synchronized void saveFile(BranchKey key, Long userId, String filePath, String code) {
        String path = requirePath(filePath);
        Path mine = inside(filesDir(key, userId), path);

        if (!Files.isRegularFile(mine)) {
            fork(key, userId, path, null);
        }

        write(mine, code == null ? "" : code);
    }

    /** 샌드박스에서 파일을 지운다. 팀 파일은 그대로다(반영할 때 지워진다). */
    public synchronized void deleteFile(BranchKey key, Long userId, String filePath) {
        String path = requirePath(filePath);
        Path mine = inside(filesDir(key, userId), path);
        Path base = inside(baseDir(key, userId), path);

        if (!Files.isRegularFile(base)) {
            String live = readLive(key, path);

            if (live != null) {
                // 반영할 때 "내가 지운 뒤 팀이 고쳤는지"를 가리려면 지운 순간의 내용이 필요하다.
                write(base, live);
            }
        }

        deleteIfExists(mine);

        // 팀 쪽에 있던 파일만 삭제로 적는다. 샌드박스에서 새로 만든 파일은 그냥 없애면 된다.
        if (Files.isRegularFile(base)) {
            Meta meta = readMeta(key, userId);
            Set<String> deleted = new TreeSet<>(meta.deleted());
            deleted.add(path);
            writeMeta(key, userId, new Meta(meta.enabled(), List.copyOf(deleted)));
        }
    }

    // ── 반영 ───────────────────────────────────────────────────────────

    /**
     * 파일 하나를 팀 파일에 합치면 어떻게 되는지 판정한다. 아무것도 쓰지 않는다.
     *
     * @param liveContent 브라우저가 팀 공유 문서에서 읽은 지금 내용. 없으면(null) 디스크
     */
    public synchronized MergeView merge(BranchKey key, Long userId, String filePath, String liveContent) {
        String path = requirePath(filePath);
        boolean roomOpen = collaborationHandler.isRoomOpen(roomOf(key, path));

        String live = liveContent != null ? liveContent : readLive(key, path);
        String base = readIfExists(inside(baseDir(key, userId), path));
        String mine = readIfExists(inside(filesDir(key, userId), path));

        if (readMeta(key, userId).deleted().contains(path)) {
            if (live == null) {
                return new MergeView(path, MergeStatus.UNCHANGED, null, roomOpen);
            }

            if (base != null && !live.equals(base)) {
                return new MergeView(path, MergeStatus.DELETE_CONFLICT, live, roomOpen);
            }

            return new MergeView(path, MergeStatus.DELETED, null, roomOpen);
        }

        if (mine == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "샌드박스에 없는 파일입니다: " + path);
        }

        if (live == null) {
            // 팀 쪽에 없다 — 샌드박스에서 새로 만들었거나, 그 사이 팀이 지웠다.
            return new MergeView(path, MergeStatus.NEW_FILE, mine, roomOpen);
        }

        // base 가 없으면 양쪽이 같은 이름의 파일을 각자 새로 만든 것이다. 빈 파일에서
        // 출발한 것으로 보고 합친다.
        ThreeWayMerger.Result result = merger.merge(base == null ? "" : base, mine, live);

        MergeStatus status = switch (result.outcome()) {
            case UNCHANGED -> MergeStatus.UNCHANGED;
            case CLEAN -> MergeStatus.CLEAN;
            case CONFLICT -> MergeStatus.CONFLICT;
        };

        return new MergeView(path, status, result.content(), roomOpen);
    }

    /**
     * 충돌을 해결했다. 해결한 내용을 내 샌드박스 파일로 저장하고, 비교 기준(base)을
     * 충돌을 계산할 때 본 팀 파일 내용으로 옮긴다.
     *
     * 기준을 옮기지 않으면 다시 반영할 때 같은 자리에서 또 충돌이 난다. 예전 기준에서 보면
     * "팀도 이 줄을 고쳤고 나도 이 줄을 (다르게) 고쳤다"가 여전히 참이기 때문이다. 기준을
     * 옮기면 "팀의 그 내용 위에 내가 이렇게 고쳤다"가 되어, 그 사이 팀이 또 고치지 않았다면
     * 깨끗하게 들어간다. 또 고쳤다면 새 기준으로 다시 3-way 병합한다.
     *
     * @param resolved    사용자가 고른 결과(충돌 표식이 없는 내용)
     * @param liveContent 충돌을 계산할 때 팀 파일로 삼았던 내용(merge 에 보냈던 것과 같은 값)
     */
    public synchronized void resolve(
            BranchKey key, Long userId, String filePath, String resolved, String liveContent
    ) {
        String path = requirePath(filePath);

        if (resolved == null || liveContent == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code 와 liveContent 가 모두 필요합니다.");
        }

        write(inside(baseDir(key, userId), path), liveContent);
        write(inside(filesDir(key, userId), path), resolved);
    }

    /**
     * 반영이 끝난 파일을 샌드박스에서 뺀다.
     *
     * 방이 열려 있는 파일은 브라우저가 팀 공유 문서에 직접 넣은 뒤 이것을 부른다.
     */
    public synchronized void markApplied(BranchKey key, Long userId, String filePath) {
        String path = requirePath(filePath);

        deleteIfExists(inside(filesDir(key, userId), path));
        deleteIfExists(inside(baseDir(key, userId), path));

        Meta meta = readMeta(key, userId);
        if (meta.deleted().contains(path)) {
            List<String> deleted = new ArrayList<>(meta.deleted());
            deleted.remove(path);
            writeMeta(key, userId, new Meta(meta.enabled(), deleted));
        }
    }

    /**
     * 서버가 팀 파일(디스크)에 직접 반영한다.
     *
     * 쓸 수 있는 경우는 둘이다.
     * <ul>
     *   <li>그 파일의 팀 공유 방이 <b>닫혀 있을 때</b>. 열려 있는데 디스크만 고치면, 방의
     *       문서는 옛 내용이라 자동 저장이 곧 디스크를 옛 내용으로 덮는다. 그때는
     *       RoomOpenException 을 던져 브라우저 쪽 반영을 쓰게 한다.</li>
     *   <li>새 파일 만들기와 파일 지우기. 이것은 문서 편집으로 할 수 없어서 방 상태와
     *       무관하게 서버가 한다. 지운 파일을 열어 둔 사람의 자동 저장이 파일을 되살리는
     *       것은 FileService.saveFile 이 막는다.</li>
     * </ul>
     *
     * @return 판정 결과. CONFLICT / DELETE_CONFLICT 면 아무것도 하지 않은 것이다
     */
    public synchronized MergeView applyDirect(BranchKey key, Long userId, String filePath) {
        String path = requirePath(filePath);
        MergeView view = merge(key, userId, path, null);

        switch (view.status()) {
            case CONFLICT, DELETE_CONFLICT -> {
                return view;
            }
            case UNCHANGED -> {
            }
            case NEW_FILE -> {
                fileService.saveFile(liveRequest(key, path, view.content()));
                broadcast(key, path, "CREATE");
                authorStatusService.recordEdit(key, path, userId);
                authorStatusService.onFileSaved(key, path, view.content());
            }
            case DELETED -> {
                // 화면에서 파일을 지울 때(IdeController.deleteFile)와 같은 순서다.
                fileService.deleteFile(liveRequest(key, path, null));
                broadcast(key, path, "DELETE");
                collaborationHandler.dropDocsUnder(roomOf(key, path));
                authorStatusService.recordEdit(key, path, userId);
                authorStatusService.onFileRemoved(key, path);
            }
            case CLEAN -> {
                boolean written = collaborationHandler.runIfRoomClosed(
                        roomOf(key, path),
                        () -> fileService.saveFile(liveRequest(key, path, view.content()))
                );

                if (!written) {
                    throw new RoomOpenException(path);
                }

                broadcast(key, path, "UPDATE");
                authorStatusService.recordEdit(key, path, userId);
                authorStatusService.onFileSaved(key, path, view.content());
            }
        }

        markApplied(key, userId, path);
        return view;
    }

    /** 버린다. 내 샌드박스 수정이 모두 사라진다. */
    public synchronized void discard(BranchKey key, Long userId) {
        try {
            RunAssembler.deleteRecursively(paths.sandbox(key, userId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 실행할 때 덮을 내 샌드박스. 고친 것이 없으면 null. */
    public synchronized Overlay overlay(BranchKey key, Long userId) {
        if (!Files.isDirectory(paths.sandbox(key, userId))) {
            return null;
        }

        return new Overlay(filesDir(key, userId), Set.copyOf(readMeta(key, userId).deleted()));
    }

    /** 방이 열려 있어 서버가 디스크에 직접 쓸 수 없다. */
    public static class RoomOpenException extends RuntimeException {
        public RoomOpenException(String filePath) {
            super("팀원이 열어 둔 파일입니다. 브라우저에서 반영해야 합니다: " + filePath);
        }
    }

    // ── 내부 ───────────────────────────────────────────────────────────

    private Path filesDir(BranchKey key, Long userId) {
        return paths.sandbox(key, userId).resolve("files");
    }

    private Path baseDir(BranchKey key, Long userId) {
        return paths.sandbox(key, userId).resolve("base");
    }

    private Path metaFile(BranchKey key, Long userId) {
        return paths.sandbox(key, userId).resolve("meta.json");
    }

    private String roomOf(BranchKey key, String path) {
        return CollabRoomName.fileRoom(key.workspaceId(), key.projectName(), key.branchName(), path);
    }

    private Meta readMeta(BranchKey key, Long userId) {
        Path file = metaFile(key, userId);

        if (!Files.isRegularFile(file)) {
            return new Meta(false, List.of());
        }

        try {
            Meta meta = objectMapper.readValue(file.toFile(), Meta.class);
            return new Meta(meta.enabled(), meta.deleted() == null ? List.of() : meta.deleted());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeMeta(BranchKey key, Long userId, Meta meta) {
        try {
            Path file = metaFile(key, userId);
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), meta);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 팀 파일의 디스크 내용. 없으면 null. 읽기만 한다. */
    private String readLive(BranchKey key, String path) {
        return readIfExists(inside(paths.live(key), path));
    }

    private FileRequest liveRequest(BranchKey key, String path, String code) {
        return FileRequest.builder()
                .workspaceId(key.workspaceId())
                .projectName(key.projectName())
                .branchName(key.branchName())
                .filePath(path)
                .code(code)
                .type("file")
                // 사용자가 누른 반영이다. 빈 파일로 만드는 것도, 없는 파일을 만드는 것도 의도다.
                .allowEmpty(true)
                .build();
    }

    private void broadcast(BranchKey key, String path, String action) {
        FileRequest event = liveRequest(key, path, null);
        event.setNewName("");
        workspaceEventHandler.broadcastFileTreeChanged(event, action);
    }

    private static String requirePath(String filePath) {
        String path = EditLedger.normalizePath(filePath);

        if (path.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "파일 경로가 비어 있습니다.");
        }

        return path;
    }

    private static Path inside(Path root, String path) {
        Path resolved = RunAssembler.resolveInside(root, path);

        if (resolved == null) {
            throw new SecurityException("잘못된 파일 접근입니다.");
        }

        return resolved;
    }

    private static String readIfExists(Path file) {
        return Files.isRegularFile(file) ? read(file) : null;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteIfExists(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
