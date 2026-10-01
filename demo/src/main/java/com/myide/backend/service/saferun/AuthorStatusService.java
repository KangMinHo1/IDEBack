// 경로: src/main/java/com/myide/backend/service/saferun/AuthorStatusService.java
package com.myide.backend.service.saferun;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 작성자마다 READY(준비됨) / EDITING(수정 중)을 계산하고, 바뀔 때만 알린다.
 *
 * 검사 시점이 "입력이 멈춘 뒤"가 아니라 <b>파일이 저장될 때</b>인 이유:
 * 서버는 동시 편집 문서를 읽지 못해서 내용을 볼 수 있는 곳이 디스크뿐이다. 디스크는
 * 저장 담당자가 입력이 3초 멈추면(최대 30초) 써 준다. 그 저장 요청에 내용이 실려
 * 오므로 그때 검사한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorStatusService implements CollabEditListener {

    /** 상태가 바뀌었을 때 불린다. 같은 브랜치를 보는 사람들에게 알리는 쪽이 구현한다. */
    public interface ChangeListener {
        void onStatusChanged(BranchKey key);
    }

    private final EditLedger ledger;
    private final SyntaxChecker syntaxChecker;
    private final ChangeListener changeListener;

    /** 브랜치별로 "지금 문법이 깨져 있는 파일". */
    private final Map<BranchKey, Set<String>> brokenFiles = new ConcurrentHashMap<>();

    /** 마지막으로 알린 상태. 다시 계산한 결과가 이것과 같으면 알리지 않는다. */
    private final Map<BranchKey, Map<Long, AuthorStatus>> announced = new ConcurrentHashMap<>();

    @Override
    public void onEdit(String workspaceId, String projectName, String branchName, String filePath, Long userId) {
        recordEdit(BranchKey.of(workspaceId, projectName, branchName), filePath, userId);
    }

    /** 편집·파일 생성·삭제를 장부에 적는다. 새 작성자가 생겼을 때만 상태를 다시 계산한다. */
    public void recordEdit(BranchKey key, String filePath, Long userId) {
        if (ledger.record(key, filePath, userId)) {
            refresh(key);
        }
    }

    /**
     * 파일이 디스크에 저장됐다. 장부에 있는 파일이면 문법을 검사한다.
     *
     * 장부에 없는 파일은 검사하지 않는다. 누구의 상태에도 영향을 주지 않기 때문이다.
     */
    public void onFileSaved(BranchKey key, String filePath, String code) {
        String path = EditLedger.normalizePath(filePath);

        if (!ledger.contains(key, path)) {
            return;
        }

        Set<String> broken = brokenFiles.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());

        if (syntaxChecker.isValid(path, code)) {
            broken.remove(path);
        } else {
            broken.add(path);
        }

        refresh(key);
    }

    /** 파일이 지워졌으면 더는 깨진 파일이 아니다. */
    public void onFileRemoved(BranchKey key, String filePath) {
        Set<String> broken = brokenFiles.get(key);

        if (broken != null && broken.remove(EditLedger.normalizePath(filePath))) {
            refresh(key);
        }
    }

    /** 지금 상태(작성자 → 상태). 장부에 이름이 있는 사람만 나온다. */
    public Map<Long, AuthorStatus> statuses(BranchKey key) {
        Set<String> broken = brokenFiles.getOrDefault(key, Set.of());
        Map<Long, AuthorStatus> result = new TreeMap<>();

        ledger.snapshot(key).forEach((path, authors) -> {
            boolean isBroken = broken.contains(path);

            for (Long author : authors) {
                if (isBroken) {
                    result.put(author, AuthorStatus.EDITING);
                } else {
                    result.putIfAbsent(author, AuthorStatus.READY);
                }
            }
        });

        return result;
    }

    /**
     * 상태를 다시 계산하고, 마지막으로 알린 것과 다르면 알린다.
     *
     * green 이 갱신돼 장부가 줄었을 때도 부른다(작성자가 목록에서 빠진다).
     */
    public void refresh(BranchKey key) {
        Map<Long, AuthorStatus> current = statuses(key);

        // 장부에서 사라진 파일은 깨진 목록에서도 뺀다. 남겨 두면 같은 경로를 다시
        // 고치기 시작한 사람이 검사도 받기 전에 EDITING 으로 뜬다.
        Set<String> broken = brokenFiles.get(key);
        if (broken != null) {
            broken.removeIf(path -> !ledger.contains(key, path));
        }

        Map<Long, AuthorStatus> previous = announced.put(key, current);

        if (current.equals(previous) || (previous == null && current.isEmpty())) {
            return;
        }

        try {
            changeListener.onStatusChanged(key);
        } catch (RuntimeException e) {
            // 알림 실패가 편집 중계나 파일 저장을 막아서는 안 된다.
            log.warn("⚠️ [SafeRun] 작성자 상태 알림 실패: {}", e.getMessage());
        }
    }
}
