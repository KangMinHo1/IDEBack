package com.myide.backend.service.aireport;

import java.util.List;

/**
 * AI 에 넘길 보고서 자료 한 벌. 캐시에 그대로 들어간다.
 *
 * @param input    프로젝트·개발일지·설계를 정리한 글
 * @param features 응답 검사에 다시 쓸 기능 목록(구현 근거 포함)
 */
public record ReportBrief(String input, List<ReportFeature> features) {

    public ReportBrief {
        features = features == null ? List.of() : List.copyOf(features);
    }
}
