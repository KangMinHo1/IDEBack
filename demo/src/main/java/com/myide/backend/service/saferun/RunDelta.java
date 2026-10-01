// 경로: src/main/java/com/myide/backend/service/saferun/RunDelta.java
package com.myide.backend.service.saferun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 앞선 시도의 실행 폴더와 이번 시도의 실행 폴더 사이에서 달라진 파일.
 *
 * 한 번의 실행은 컨테이너 하나를 재사용한다. 조합을 바꿔 다시 빌드할 때 폴더 전체를
 * 다시 넣지 않고 달라진 파일만 바꿔 넣어야, 컨테이너 안에 쌓인 빌드 결과(gradle 의
 * 증분 컴파일 등)가 살아남아 두 번째 빌드부터 빨라진다.
 *
 * @param changed 내용이 바뀌었거나 새로 생긴 파일(실행 폴더 기준 상대경로)
 * @param removed 이번 조합에는 없는 파일
 */
public record RunDelta(List<String> changed, List<String> removed) {

    public static RunDelta between(Path previous, Path next) throws IOException {
        Set<String> before = filesOf(previous);
        Set<String> after = filesOf(next);

        List<String> changed = new ArrayList<>();
        for (String path : after) {
            if (!before.contains(path) || Files.mismatch(previous.resolve(path), next.resolve(path)) != -1) {
                changed.add(path);
            }
        }

        List<String> removed = new ArrayList<>(before);
        removed.removeAll(after);

        return new RunDelta(changed, removed);
    }

    public boolean isEmpty() {
        return changed.isEmpty() && removed.isEmpty();
    }

    /** 바뀐 파일만 같은 폴더 구조로 모아 둔다. 이 폴더를 컨테이너에 덮어쓰면 된다. */
    public void copyChangedTo(Path next, Path deltaDir) throws IOException {
        for (String path : changed) {
            Path target = deltaDir.resolve(path);
            Files.createDirectories(target.getParent());
            Files.copy(next.resolve(path), target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 없어진 파일을 컨테이너에서 지우는 명령. 지울 것이 없으면 빈 문자열. */
    public String removeShell() {
        if (removed.isEmpty()) {
            return "";
        }

        return "rm -f -- " + removed.stream().map(RunCommands::quote).collect(Collectors.joining(" "));
    }

    private static Set<String> filesOf(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(file -> root.relativize(file).toString().replace("\\", "/"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
