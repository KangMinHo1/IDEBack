package com.myide.backend.service.aireport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.aireport.FinalReportDraftRequest;
import com.myide.backend.repository.design.DesignSnapshotVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 보고서 자료(AI 에 넘기기 직전의 글) 캐시.
 *
 * 키는 워크스페이스 + 설계 저장본 버전 + 요청 본문 해시다.
 *  - 설계가 저장되면 revision 이 올라 키가 바뀐다. 매 요청마다 버전을 DB 에서 다시
 *    읽으므로(큰 칸 없이 한 줄) 재생성하면 언제나 최신 설계가 반영된다.
 *  - 개발일지와 프로젝트 정보는 요청에 담겨 오므로 본문 해시로 변화를 잡는다.
 *  - 같은 워크스페이스의 새 키가 들어오면 그 워크스페이스의 옛 항목은 지운다.
 *
 * AI 응답은 캐시하지 않는다. 사용자가 다시 생성을 누르면 새 글을 기대하기 때문이다.
 * 서버 한 대 메모리에만 두므로 재시작하면 비고, 그래도 결과는 같다.
 */
@Component
@RequiredArgsConstructor
public class ReportBriefCache {

    static final int MAX_ENTRIES = 64;
    static final Duration TTL = Duration.ofMinutes(30);

    private final ObjectMapper objectMapper;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private Clock clock = Clock.systemUTC();

    private record Entry(String workspaceId, ReportBrief brief, Instant storedAt) {
    }

    public String key(String workspaceId, Optional<DesignSnapshotVersion> version,
                      FinalReportDraftRequest request) {
        return workspaceId + "|" + version.map(DesignSnapshotVersion::asKey).orElse("none")
                + "|" + hash(request);
    }

    public synchronized Optional<ReportBrief> get(String key) {
        Entry entry = entries.get(key);

        if (entry == null) {
            return Optional.empty();
        }

        if (entry.storedAt().plus(TTL).isBefore(clock.instant())) {
            entries.remove(key);
            return Optional.empty();
        }

        return Optional.of(entry.brief());
    }

    public synchronized void put(String workspaceId, String key, ReportBrief brief) {
        // 같은 워크스페이스의 옛 자료는 더 쓸 일이 없다. 설계나 일지가 바뀌었다는 뜻이다.
        entries.values().removeIf(entry -> entry.workspaceId().equals(workspaceId));
        entries.put(key, new Entry(workspaceId, brief, clock.instant()));

        Iterator<String> eldest = entries.keySet().iterator();
        while (entries.size() > MAX_ENTRIES && eldest.hasNext()) {
            eldest.next();
            eldest.remove();
        }
    }

    synchronized int size() {
        return entries.size();
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private String hash(FinalReportDraftRequest request) {
        try {
            byte[] body = objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            // 해시를 못 만들면 캐시를 쓰지 않는 것과 같게 매번 다른 키를 준다.
            return "nohash-" + System.nanoTime();
        }
    }
}
