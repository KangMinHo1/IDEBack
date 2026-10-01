// 경로: src/main/java/com/myide/backend/service/saferun/EditLedger.java
package com.myide.backend.service.saferun;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 편집 장부. 마지막 정상 버전(green) 이후 "어떤 파일을 누가 고쳤는지"를 브랜치별로 적는다.
 *
 * 내용은 적지 않는다. 서버는 동시 편집 문서를 해석하지 못하므로 알 수 있는 것은
 * "이 사람이 이 파일에 편집을 보냈다"는 사실뿐이고, 실행 폴더를 조립할 때도 그것만
 * 있으면 된다(파일을 통째로 넣을지 뺄지만 정하므로).
 *
 * 메모리에만 둔다. 서버를 재시작하면 사라지는데, 그때 생기는 "장부에 없는 변경"은
 * RunAssembler 가 따로 처리하므로 실행에서 빠지지 않는다.
 */
@Component
public class EditLedger {

    private final Map<BranchKey, Map<String, Set<Long>>> entries = new ConcurrentHashMap<>();

    /**
     * 장부에 쓰는 파일 경로 형식. 브랜치 폴더 기준 상대경로이고 구분자는 '/' 다.
     * 방 이름·REST 요청·컴파일러 출력이 제각각 다른 모양으로 경로를 주므로 한곳에서 맞춘다.
     */
    public static String normalizePath(String filePath) {
        return String.valueOf(filePath == null ? "" : filePath)
                .replace("\\", "/")
                .replaceAll("^(\\./)+", "")
                .replaceAll("^/+", "")
                .replaceAll("/+", "/")
                .trim();
    }

    /**
     * 편집을 기록한다.
     *
     * @return 이 파일에 이 작성자가 처음 적혔으면 true. 글자를 칠 때마다 불리므로,
     *         부르는 쪽은 true 일 때만 상태를 다시 계산하면 된다.
     */
    public boolean record(BranchKey key, String filePath, Long userId) {
        String path = normalizePath(filePath);

        if (key == null || userId == null || path.isEmpty()) {
            return false;
        }

        return entries
                .computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(path, p -> ConcurrentHashMap.newKeySet())
                .add(userId);
    }

    public boolean contains(BranchKey key, String filePath) {
        Map<String, Set<Long>> files = entries.get(key);
        return files != null && files.containsKey(normalizePath(filePath));
    }

    /** 이 파일을 고친 사람들. 없으면 빈 집합. */
    public Set<Long> authorsOf(BranchKey key, String filePath) {
        Map<String, Set<Long>> files = entries.get(key);

        if (files == null) {
            return Set.of();
        }

        Set<Long> authors = files.get(normalizePath(filePath));
        return authors == null ? Set.of() : Set.copyOf(authors);
    }

    /**
     * 지금 장부의 사본(파일경로 → 작성자 집합).
     *
     * 실행 폴더를 조립하는 동안에도 편집은 계속 들어온다. 조립과 그 뒤의 판단이 같은
     * 내용을 보도록 사본을 떠서 쓴다.
     */
    public Map<String, Set<Long>> snapshot(BranchKey key) {
        Map<String, Set<Long>> files = entries.get(key);
        Map<String, Set<Long>> copy = new HashMap<>();

        if (files != null) {
            files.forEach((path, authors) -> {
                if (!authors.isEmpty()) {
                    copy.put(path, Set.copyOf(authors));
                }
            });
        }

        return copy;
    }

    /** 장부에 이름이 올라 있는 사람 전부. */
    public Set<Long> allAuthors(BranchKey key) {
        Set<Long> all = new TreeSet<>();
        snapshot(key).values().forEach(all::addAll);
        return all;
    }

    /** 조건에 맞는 파일을 장부에서 지운다. green 을 갱신한 뒤 "이제 green 과 같은 파일"을 지울 때 쓴다. */
    public void removeIf(BranchKey key, Predicate<String> filePathMatches) {
        Map<String, Set<Long>> files = entries.get(key);

        if (files != null) {
            files.keySet().removeIf(filePathMatches);
        }
    }

    public void clear(BranchKey key) {
        entries.remove(key);
    }
}
