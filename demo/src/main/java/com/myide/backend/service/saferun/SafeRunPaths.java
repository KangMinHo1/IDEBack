// 경로: src/main/java/com/myide/backend/service/saferun/SafeRunPaths.java
package com.myide.backend.service.saferun;

import com.myide.backend.service.WorkspaceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 안전 실행·샌드박스가 쓰는 폴더의 위치를 정하는 단일 창구.
 *
 * <pre>
 *   {root}/{workspaceId}/green/{project}/{branchFolder}/                 마지막으로 컴파일에 성공한 사본
 *   {root}/{workspaceId}/green/{project}/{branchFolder}.stamp            그 사본을 만든 시각
 *   {root}/{workspaceId}/sandbox/{project}/{branchFolder}/{userId}/      개인 샌드박스
 *   {root}/runs/{runId}/                                                 실행용 임시 폴더
 * </pre>
 *
 * 워크스페이스 폴더 안에 두지 않는 이유는, 워크스페이스가 사용자 개인 폴더 안에 있어
 * 폴더 선택 창과 파일 트리에 그대로 보이기 때문이다. git 이 추적하는 곳과도 섞인다.
 *
 * 루트는 설정값(ide.safe-run-root)이다. 로컬은 Windows, 운영은 Linux 라 경로가 다르므로
 * 운영에서는 환경변수 IDE_SAFE_RUN_ROOT 로 지정한다.
 */
@Component
public class SafeRunPaths {

    private final Path root;
    private final WorkspaceService workspaceService;

    public SafeRunPaths(@Value("${ide.safe-run-root}") String root, WorkspaceService workspaceService) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
        this.workspaceService = workspaceService;
    }

    /** 워크스페이스 하나에 딸린 안전 실행 폴더. 워크스페이스를 지울 때 이것도 함께 지운다. */
    public static Path workspaceRoot(String safeRunRoot, String workspaceId) {
        Path base = Paths.get(safeRunRoot).toAbsolutePath().normalize();
        return inside(base, base.resolve(workspaceId));
    }

    /** 팀이 실시간으로 함께 고치는 기존 브랜치 폴더. 안전 실행은 이 폴더에 쓰지 않는다. */
    public Path live(BranchKey key) {
        return workspaceService.getProjectPath(key.workspaceId(), key.projectName(), key.branchName());
    }

    public Path green(BranchKey key) {
        return branchDir(key, "green");
    }

    public Path greenStamp(BranchKey key) {
        Path green = green(key);
        return green.resolveSibling(green.getFileName() + ".stamp");
    }

    public Path sandbox(BranchKey key, Long userId) {
        return inside(root, branchDir(key, "sandbox").resolve(String.valueOf(userId)));
    }

    public Path runsRoot() {
        return root.resolve("runs");
    }

    public Path run(String runId) {
        return inside(root, runsRoot().resolve(runId));
    }

    private Path branchDir(BranchKey key, String kind) {
        // 브랜치 이름의 '/' 가 폴더로 쪼개지지 않도록 live 폴더와 같은 인코딩을 쓴다
        // (feature/login → feature%2Flogin).
        String branchFolder = workspaceService.toBranchFolderName(key.branchName());

        return inside(root, root
                .resolve(key.workspaceId())
                .resolve(kind)
                .resolve(key.projectName())
                .resolve(branchFolder));
    }

    /** 이름에 ".." 같은 것이 섞여 루트 밖으로 나가는 경로를 막는다. */
    private static Path inside(Path base, Path candidate) {
        Path normalized = candidate.normalize();

        if (!normalized.startsWith(base) || normalized.equals(base)) {
            throw new SecurityException("잘못된 경로입니다.");
        }

        return normalized;
    }
}
