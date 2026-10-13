// 경로: src/main/java/com/myide/backend/service/sandbox/ThreeWayMerger.java
package com.myide.backend.service.sandbox;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 샌드박스 파일 하나를 팀 파일에 합칠 때의 판정.
 *
 * 세 가지를 본다 — base(샌드박스에서 고치기 시작한 순간의 팀 파일), mine(내 샌드박스 파일),
 * live(지금의 팀 파일).
 *
 * <pre>
 *   mine == base            내가 바꾼 것이 없다        → UNCHANGED
 *   live == base            그동안 팀이 안 건드렸다    → CLEAN (mine 그대로)
 *   mine == live            둘이 같은 내용이 됐다      → UNCHANGED
 *   그 외                   git merge-file 로 3-way 병합
 *                             겹치지 않으면            → CLEAN (병합본)
 *                             겹치면                   → CONFLICT (충돌 표식이 든 본문)
 * </pre>
 *
 * 충돌 본문은 git 의 표준 표식(<<<<<<< / ======= / >>>>>>>)을 쓴다. 에디터의 병합 충돌
 * 화면(CodeEditor 의 "내 것 / 상대 것 / 둘 다")이 그 형식을 그대로 읽는다. 위쪽이 내 것이다.
 */
@Component
public class ThreeWayMerger {

    public enum Outcome {
        UNCHANGED,
        CLEAN,
        CONFLICT
    }

    /** @param content CLEAN 이면 팀 파일에 넣을 내용, CONFLICT 면 충돌 표식이 든 본문, UNCHANGED 면 live 그대로 */
    public record Result(Outcome outcome, String content) {}

    public Result merge(String base, String mine, String live) {
        if (mine.equals(base) || mine.equals(live)) {
            return new Result(Outcome.UNCHANGED, live);
        }

        if (live.equals(base)) {
            return new Result(Outcome.CLEAN, mine);
        }

        return mergeWithGit(base, mine, live);
    }

    /**
     * 줄 단위 3-way 병합은 git 에게 맡긴다. 저장소가 필요 없는 명령이라 임시 폴더에
     * 파일 셋만 놓고 돌린다. 서버는 이미 GitService 가 git CLI 를 쓰고 있어 새로 깔 것이 없다.
     */
    private Result mergeWithGit(String base, String mine, String live) {
        Path workDir = null;

        try {
            workDir = Files.createTempDirectory("vside-merge-");

            Path mineFile = workDir.resolve("mine");
            Path baseFile = workDir.resolve("base");
            Path liveFile = workDir.resolve("live");

            Files.writeString(mineFile, mine, StandardCharsets.UTF_8);
            Files.writeString(baseFile, base, StandardCharsets.UTF_8);
            Files.writeString(liveFile, live, StandardCharsets.UTF_8);

            // -p: 결과를 파일에 덮어쓰지 않고 표준 출력으로 낸다.
            // -L: 충돌 표식 옆에 붙는 이름. 임시 파일 경로가 그대로 보이지 않게 한다.
            Process process = new ProcessBuilder(
                    "git", "merge-file", "-p",
                    "-L", "sandbox", "-L", "base", "-L", "team",
                    mineFile.toString(), baseFile.toString(), liveFile.toString()
            ).directory(workDir.toFile()).redirectErrorStream(false).start();

            // 줄바꿈을 그대로 보존해야 하므로 줄 단위로 읽지 않고 통째로 읽는다.
            byte[] merged = process.getInputStream().readAllBytes();
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();

            // 0 = 깨끗하게 병합, 1~127 = 충돌 개수, 그 밖 = 명령 자체가 실패.
            if (exitCode == 0) {
                return new Result(Outcome.CLEAN, new String(merged, StandardCharsets.UTF_8));
            }

            if (exitCode > 0 && exitCode <= 127) {
                return new Result(Outcome.CONFLICT, new String(merged, StandardCharsets.UTF_8));
            }

            throw new IllegalStateException("git merge-file 실패(" + exitCode + "): " + error.trim());
        } catch (IOException e) {
            throw new IllegalStateException("병합에 실패했습니다: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("병합이 중단되었습니다.", e);
        } finally {
            deleteQuietly(workDir);
        }
    }

    private void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }

        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }
}
