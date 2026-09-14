package com.myide.backend.dto.design.v2;

/**
 * 설계 한 단계(요구사항 / 화면 / API / 테이블)의 진행 상황.
 *
 * 숫자를 두 가지로 나눠 담는다.
 *
 *  - passedChecks / totalChecks : 체크칸 단위. 항목 하나를 반쯤 채워도 막대가 움직인다.
 *  - itemsDone / itemCount      : 완전히 끝난 항목 수. "8개 중 6개 완료" 로 읽힌다.
 *
 * 막대만 있으면 얼마나 남았는지 감이 안 오고, 완료 개수만 있으면 네 칸 중 세 칸을
 * 채워도 화면이 꿈쩍하지 않아 사람이 지친다. 그래서 둘 다 준다.
 *
 * itemCount 가 0 이면 percent 는 0 이지만, 화면은 이때 0% 대신 "아직 없음" 으로
 * 보여 준다. 아무것도 안 만든 단계를 0% 라고 하는 것도, 100% 라고 하는 것도
 * 사실과 다르다.
 */
public record StageProgress(
        int itemCount,
        int itemsDone,
        int passedChecks,
        int totalChecks,
        int percent
) {
    public static StageProgress of(int itemCount, int itemsDone, int passedChecks, int totalChecks) {
        return new StageProgress(
                itemCount,
                itemsDone,
                passedChecks,
                totalChecks,
                percentOf(passedChecks, totalChecks)
        );
    }

    public static int percentOf(int passed, int total) {
        if (total <= 0) {
            return 0;
        }

        return (int) Math.round((passed * 100.0) / total);
    }
}
