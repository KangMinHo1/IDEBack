package com.myide.backend.service.aireport;

import com.myide.backend.dto.aireport.FinalReportDraftRequest;
import com.myide.backend.repository.design.DesignSnapshotVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FinalReportService {

    private final FinalReportPromptBuilder promptBuilder;
    private final GeminiReportClient geminiReportClient;
    private final DesignReportSectionBuilder designSectionBuilder;
    private final ReportBriefCache briefCache;
    private final FinalReportValidator validator;

    /** 한 번 생성의 결과와 단계별 측정값. 로그와 성능 비교에 쓴다. */
    record Outcome(
            String draft,
            GeminiReportClient.ReportGeneration generation,
            FinalReportValidator.Result check,
            boolean cacheHit,
            long prepareMillis,
            long totalMillis,
            int inputChars
    ) {
    }

    public String generateDraft(String workspaceId, FinalReportDraftRequest request) {
        return generate(workspaceId, request).draft();
    }

    Outcome generate(String workspaceId, FinalReportDraftRequest request) {
        validateRequest(workspaceId, request);

        long startedAt = System.nanoTime();

        // 설계는 요청에 담겨 온 값 대신 저장된 것을 읽는다. 버전은 매번 DB 에서 다시
        // 확인하므로(큰 칸 없이) 설계가 바뀌면 캐시를 건너뛰고 최신 자료로 만든다.
        Optional<DesignSnapshotVersion> version = designSectionBuilder.currentVersion(workspaceId);
        String cacheKey = briefCache.key(workspaceId, version, request);

        Optional<ReportBrief> cached = briefCache.get(cacheKey);
        ReportBrief brief;

        if (cached.isPresent()) {
            brief = cached.get();
        } else {
            DesignReportSectionBuilder.DesignBrief design = version.isPresent()
                    ? designSectionBuilder.build(workspaceId, request.getDevlogs())
                    : null;
            brief = promptBuilder.buildBrief(request, design);
            briefCache.put(workspaceId, cacheKey, brief);
        }

        String instructions = promptBuilder.buildInstructions();
        long prepareMillis = elapsedMillis(startedAt);

        GeminiReportClient.ReportGeneration generation =
                geminiReportClient.generate(instructions, brief.input());

        FinalReportValidator.Result check =
                validator.check(generation.text(), brief.features(), generation.truncated());
        String draft = validator.completeSections(generation.text(), check);

        long totalMillis = elapsedMillis(startedAt);

        // 본문 대신 길이와 수치만 남긴다. 어느 단계가 느린지, 품질 검사에 걸렸는지 보려는 로그다.
        log.info("⏱️ [보고서] workspace={} 준비={}ms(캐시 {}) 입력={}자 AI={}ms 전체={}ms 결과={}자 model={}",
                workspaceId, prepareMillis, cached.isPresent() ? "적중" : "새로 만듦",
                instructions.length() + brief.input().length(),
                generation.apiMillis(), totalMillis, draft.length(), generation.model());

        if (!check.structureOk() || !check.qualityOk()) {
            log.warn("⚠️ [보고서 검사] workspace={} 빠진 항목={} 잘림={} 명세복사={}줄({}%) 문단비율={}% "
                            + "근거없는 구현 주장={} 근거없는 미구현 단정={} 빠진 핵심 기능={} 한글 발음 표기={} 마크다운={}개",
                    workspaceId, check.missingSections(), check.truncated(), check.specCopyLines(),
                    Math.round(check.specCopyRatio() * 100), Math.round(check.proseRatio() * 100),
                    check.unsupportedClaims(), check.deniedWithoutEvidence(), check.missingKeyFeatures(),
                    check.transliteratedTerms(), check.markdownMarks());
        }

        return new Outcome(draft, generation, check, cached.isPresent(), prepareMillis, totalMillis,
                instructions.length() + brief.input().length());
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private void validateRequest(String workspaceId, FinalReportDraftRequest request) {
        if (!StringUtils.hasText(workspaceId)) {
            throw new IllegalArgumentException("workspaceId가 없습니다.");
        }

        if (request == null) {
            throw new IllegalArgumentException("최종 보고서 생성 요청 데이터가 없습니다.");
        }

        if (request.getProject() == null) {
            throw new IllegalArgumentException("프로젝트 정보가 없습니다.");
        }
    }
}
