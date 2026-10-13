// 경로: src/main/java/com/myide/backend/service/saferun/GreenService.java
package com.myide.backend.service.saferun;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 정상 버전(green)을 갱신한다.
 *
 * green 을 만들려고 따로 빌드를 돌리지 않는다. 누군가 실행했을 때 "아무도 빠지지 않은
 * 조합"으로 컴파일이 성공하면, 그 실행 폴더가 곧 새 정상 버전이다. 2GB 서버에서 빌드는
 * 비싸므로 어차피 하는 빌드의 결과를 재활용한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GreenService {

    private final SafeRunPaths paths;
    private final EditLedger ledger;

    /** 두 사람이 동시에 실행해 같은 브랜치의 green 을 동시에 갈아 끼우는 것을 막는다. */
    private final Map<BranchKey, ReentrantLock> locks = new ConcurrentHashMap<>();

    public boolean exists(BranchKey key) {
        return Files.isDirectory(paths.green(key));
    }

    /**
     * 컴파일에 성공한 실행 폴더를 새 정상 버전으로 삼고, 장부에서 "이제 green 과 같은
     * 파일"을 지운다.
     *
     * 장부를 통째로 비우지 않는 이유: 빌드하는 동안에도 팀원은 계속 고친다. 그 사이에
     * 다시 고친 파일은 live 가 green 과 달라져 있으므로 장부에 남아야 한다.
     */
    public void promote(BranchKey key, Path runDir) throws IOException {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();

        try {
            Path green = paths.green(key);
            Path staging = green.resolveSibling(green.getFileName() + ".tmp-" + UUID.randomUUID());

            Files.createDirectories(green.getParent());

            // 옆에 다 만들어 놓고 바꿔 끼운다. 복사 도중에 실패해도 기존 green 은 멀쩡하다.
            try {
                RunAssembler.copyTree(runDir, staging);
                RunAssembler.deleteRecursively(green);
                Files.move(staging, green);
            } finally {
                RunAssembler.deleteRecursively(staging);
            }

            Files.writeString(paths.greenStamp(key), String.valueOf(System.currentTimeMillis()));

            Path live = paths.live(key);
            ledger.removeIf(key, path -> sameContent(live.resolve(path), green.resolve(path)));

            log.info("✅ [SafeRun] 정상 버전 갱신: {}", key);
        } finally {
            lock.unlock();
        }
    }

    /** 두 경로의 내용이 같은가. 둘 다 없으면(둘 다 지워졌으면) 같은 것으로 본다. */
    static boolean sameContent(Path a, Path b) {
        boolean aExists = Files.exists(a, LinkOption.NOFOLLOW_LINKS);
        boolean bExists = Files.exists(b, LinkOption.NOFOLLOW_LINKS);

        if (!aExists || !bExists) {
            return aExists == bExists;
        }

        boolean aDir = Files.isDirectory(a);
        boolean bDir = Files.isDirectory(b);

        if (aDir || bDir) {
            return aDir && bDir;
        }

        try {
            return Files.mismatch(a, b) == -1;
        } catch (IOException e) {
            // 읽지 못했으면 다르다고 본다. 장부에 남겨 두는 쪽이 안전하다.
            return false;
        }
    }
}
