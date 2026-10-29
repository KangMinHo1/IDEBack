package com.myide.backend.service.aireport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.aireport.FinalReportDraftRequest;
import com.myide.backend.repository.design.DesignSnapshotVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReportBriefCacheTest {

    private final ReportBriefCache cache = new ReportBriefCache(new ObjectMapper());

    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final ReportBrief BRIEF = new ReportBrief("자료", List.of());

    @Test
    @DisplayName("설계 revision 이 오르면 키가 바뀌어 옛 자료를 쓰지 않는다")
    void designChangeMisses() {
        FinalReportDraftRequest request = request("일지");
        String before = cache.key("ws", version(1, 3), request);
        cache.put("ws", before, BRIEF);

        String after = cache.key("ws", version(1, 4), request);

        assertThat(after).isNotEqualTo(before);
        assertThat(cache.get(after)).isEmpty();
        assertThat(cache.get(before)).contains(BRIEF);
    }

    @Test
    @DisplayName("저장본이 지워졌다 다시 생겨 revision 이 같아도 다른 키다")
    void recreatedSnapshotMisses() {
        FinalReportDraftRequest request = request("일지");

        assertThat(cache.key("ws", version(1, 1), request))
                .isNotEqualTo(cache.key("ws", version(2, 1), request));
    }

    @Test
    @DisplayName("개발일지가 바뀌면 키가 바뀐다")
    void devlogChangeMisses() {
        assertThat(cache.key("ws", version(1, 1), request("일지 A")))
                .isNotEqualTo(cache.key("ws", version(1, 1), request("일지 B")));
    }

    @Test
    @DisplayName("다른 워크스페이스와 섞이지 않는다")
    void isolatesWorkspaces() {
        FinalReportDraftRequest request = request("일지");
        String a = cache.key("ws-a", version(1, 1), request);
        String b = cache.key("ws-b", version(1, 1), request);
        cache.put("ws-a", a, BRIEF);

        assertThat(a).isNotEqualTo(b);
        assertThat(cache.get(b)).isEmpty();
    }

    @Test
    @DisplayName("같은 워크스페이스의 새 자료가 들어오면 옛 자료는 지운다")
    void replacesOldEntryOfWorkspace() {
        cache.put("ws", "old", BRIEF);
        cache.put("ws", "new", BRIEF);

        assertThat(cache.get("old")).isEmpty();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("오래된 자료는 버린다")
    void expires() {
        cache.setClock(Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
        cache.put("ws", "k", BRIEF);

        cache.setClock(Clock.fixed(Instant.parse("2026-10-01T00:31:00Z"), ZoneOffset.UTC));

        assertThat(cache.get("k")).isEmpty();
    }

    @Test
    @DisplayName("상한을 넘으면 가장 오래 안 쓴 것부터 버린다")
    void boundsSize() {
        for (int i = 0; i < ReportBriefCache.MAX_ENTRIES + 10; i++) {
            cache.put("ws-" + i, "k-" + i, BRIEF);
        }

        assertThat(cache.size()).isEqualTo(ReportBriefCache.MAX_ENTRIES);
        assertThat(cache.get("k-0")).isEmpty();
    }

    private Optional<DesignSnapshotVersion> version(long id, long revision) {
        return Optional.of(new DesignSnapshotVersion(id, revision, T));
    }

    private FinalReportDraftRequest request(String devlogSummary) {
        FinalReportDraftRequest request = new FinalReportDraftRequest();
        request.setProject(new FinalReportDraftRequest.ProjectInfo());
        FinalReportDraftRequest.DevlogInfo devlog = new FinalReportDraftRequest.DevlogInfo();
        devlog.setSummary(devlogSummary);
        request.setDevlogs(List.of(devlog));
        return request;
    }
}
