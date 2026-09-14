package com.myide.backend.dto.design.v2;

/**
 * 설계 전체 진행 상황.
 *
 * 왜 경고와 따로 두는가
 * -------------------
 * 예전에는 "이 요구사항을 담당하는 API가 없습니다" 같은 것을 경고로 뿌렸다. 그런데
 * 이건 틀린 게 아니라 아직 안 한 것이다. 요구사항을 스무 개 적으면 그 규칙 두 개만으로
 * 경고가 마흔 개가 되고, 헤더의 빨간 숫자가 수십이 되면 사람이 그 숫자를 아예 안 본다.
 * 그러면 정작 봐야 할 오류(기본키 없음, 경로 중복 같은 것)까지 같이 묻힌다.
 *
 * 그래서 "아직 안 했다"는 여기로 옮겨 진행률로 보여 주고, 빨간 배지는 오류만 센다.
 *
 * 단계 가중치를 두지 않는 이유
 * -------------------------
 * 전체 비율은 네 단계의 체크칸을 그냥 다 합쳐서 낸다. 요구사항 단계에 2배 같은 가중치를
 * 넣으면 왜 62% 인지 사용자가 설명할 수 없게 되고, 설명할 수 없는 숫자는 신뢰받지 못한다.
 */
public record ProgressReport(
        StageProgress requirements,
        StageProgress screens,
        StageProgress apis,
        StageProgress tables,
        int passedChecks,
        int totalChecks,
        int percent
) {
    public static ProgressReport of(
            StageProgress requirements,
            StageProgress screens,
            StageProgress apis,
            StageProgress tables
    ) {
        int passed = requirements.passedChecks()
                + screens.passedChecks()
                + apis.passedChecks()
                + tables.passedChecks();

        int total = requirements.totalChecks()
                + screens.totalChecks()
                + apis.totalChecks()
                + tables.totalChecks();

        return new ProgressReport(
                requirements,
                screens,
                apis,
                tables,
                passed,
                total,
                StageProgress.percentOf(passed, total)
        );
    }

    public static ProgressReport empty() {
        StageProgress none = StageProgress.of(0, 0, 0, 0);
        return of(none, none, none, none);
    }
}
