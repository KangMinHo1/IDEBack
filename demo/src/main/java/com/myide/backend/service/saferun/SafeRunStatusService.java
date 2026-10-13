// 경로: src/main/java/com/myide/backend/service/saferun/SafeRunStatusService.java
package com.myide.backend.service.saferun;

import com.myide.backend.domain.User;
import com.myide.backend.repository.UserRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 상태바에 보여 줄 내용을 만든다 — 정상 버전이 언제 것인지, 누가 준비됐고 누가 수정 중인지.
 *
 * 조회 API 와 실시간 알림이 같은 모양을 쓰도록 한곳에서 만든다.
 */
@Service
public class SafeRunStatusService {

    public record AuthorView(Long userId, String nickname, AuthorStatus status, List<String> files) {}

    public record StatusView(
            String workspaceId,
            String projectName,
            String branchName,
            Long greenUpdatedAt,
            List<AuthorView> authors,
            /** 지금 문법 오류가 있는 파일. 누가 고쳤는지는 authors 의 files 로 찾는다. */
            List<String> brokenFiles,
            /** 파일마다 마지막으로 고친 사람(파일경로 → 회원번호). */
            Map<String, Long> lastEditors
    ) {}

    private final EditLedger ledger;
    private final AuthorStatusService authorStatusService;
    private final UserRepository userRepository;
    private final SafeRunPaths paths;

    // AuthorStatusService 가 알림을 보내려고 이 서비스를 (간접적으로) 쓰기 때문에
    // 서로를 물게 된다. 한쪽을 늦게 연결해 푼다.
    public SafeRunStatusService(
            EditLedger ledger,
            @Lazy AuthorStatusService authorStatusService,
            UserRepository userRepository,
            SafeRunPaths paths
    ) {
        this.ledger = ledger;
        this.authorStatusService = authorStatusService;
        this.userRepository = userRepository;
        this.paths = paths;
    }

    public StatusView view(BranchKey key) {
        Map<Long, AuthorStatus> statuses = authorStatusService.statuses(key);
        Map<Long, Set<String>> filesByAuthor = new TreeMap<>();

        ledger.snapshot(key).forEach((path, authors) -> {
            for (Long author : authors) {
                filesByAuthor.computeIfAbsent(author, a -> new TreeSet<>()).add(path);
            }
        });

        Map<Long, String> nicknames = new TreeMap<>();
        for (User user : userRepository.findAllById(statuses.keySet())) {
            nicknames.put(user.getId(), user.getNickname());
        }

        List<AuthorView> authors = new ArrayList<>();
        statuses.forEach((userId, status) -> authors.add(new AuthorView(
                userId,
                nicknames.getOrDefault(userId, "회원 " + userId),
                status,
                List.copyOf(filesByAuthor.getOrDefault(userId, Set.of()))
        )));

        return new StatusView(
                key.workspaceId(),
                key.projectName(),
                key.branchName(),
                readGreenUpdatedAt(key),
                authors,
                List.copyOf(authorStatusService.brokenFiles(key)),
                ledger.lastEditors(key)
        );
    }

    /** 정상 버전을 만든 시각(epoch 밀리초). 아직 없으면 null. */
    private Long readGreenUpdatedAt(BranchKey key) {
        Path stamp = paths.greenStamp(key);

        try {
            return Files.exists(stamp) ? Long.valueOf(Files.readString(stamp).trim()) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
