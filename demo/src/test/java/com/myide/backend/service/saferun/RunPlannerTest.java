package com.myide.backend.service.saferun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 빌드 실패 뒤 "다음에 누구를 빼고 빌드할지"를 확인한다.
 *
 * 에러 파일 → 작성자 매핑이 틀리면 엉뚱한 사람이 제외되고, 빌드를 아낄 곳에서
 * 아끼지 못하면 2GB 서버가 버티지 못한다.
 *
 * 등장인물: 나(ME, 실행한 사람), 민수(MINSU), 지영(JIYOUNG).
 */
class RunPlannerTest {

    private static final Long ME = 1L;
    private static final Long MINSU = 2L;
    private static final Long JIYOUNG = 3L;

    private final Map<String, Set<Long>> ledger = new HashMap<>();

    private RunPlanner planner() {
        return new RunPlanner(ledger, ME, true, 3);
    }

    @Test
    @DisplayName("빌드 1 은 준비된 작성자 전원과 본인으로 시작한다")
    void firstBuildUsesReadyAuthorsAndMe() {
        ledger.put("A.java", Set.of(MINSU));
        ledger.put("B.java", Set.of(JIYOUNG));

        // 지영은 수정 중(EDITING)이라 준비된 목록에 없다. 나는 준비 여부와 무관하게 들어간다.
        RunPlanner.Attempt first = planner().first(Set.of(MINSU), null, null);

        assertThat(first.number()).isEqualTo(1);
        assertThat(first.mode()).isEqualTo(RunPlanner.Mode.FULL);
        assertThat(first.included()).containsExactlyInAnyOrder(ME, MINSU);
        assertThat(first.includeUnknownChanges()).isTrue();
    }

    @Test
    @DisplayName("사용자가 고른 포함·제외 목록을 따르되 본인은 뺄 수 없다")
    void honorsManualChoiceButNeverDropsMe() {
        RunPlanner.Attempt chosen = planner().first(Set.of(MINSU, JIYOUNG), Set.of(JIYOUNG), null);
        assertThat(chosen.included()).containsExactlyInAnyOrder(ME, JIYOUNG);

        RunPlanner.Attempt excludingMe = planner().first(Set.of(MINSU, JIYOUNG), null, Set.of(ME, MINSU));
        assertThat(excludingMe.included()).containsExactlyInAnyOrder(ME, JIYOUNG);
    }

    @Test
    @DisplayName("다른 사람이 고친 파일에서 에러가 나면 그 사람을 빼고 빌드 2 로 간다")
    void excludesTheAuthorOfTheErrorFile() {
        ledger.put("A.java", Set.of(MINSU));
        ledger.put("B.java", Set.of(JIYOUNG));
        RunPlanner planner = planner();
        RunPlanner.Attempt first = planner.first(Set.of(MINSU, JIYOUNG), null, null);

        RunPlanner.Attempt second = planner.afterFailure(first, Set.of("B.java"));

        assertThat(second.number()).isEqualTo(2);
        assertThat(second.mode()).isEqualTo(RunPlanner.Mode.EXCLUDED);
        assertThat(second.included()).containsExactlyInAnyOrder(ME, MINSU);
        assertThat(planner.excludedAuthors(second)).containsExactly(JIYOUNG);
        assertThat(planner.revertedFilesOf(JIYOUNG, second)).containsExactly("B.java");
    }

    @Test
    @DisplayName("에러 파일의 작성자가 본인뿐이면 더 빌드하지 않는다")
    void stopsWhenOnlyMyFilesFail() {
        ledger.put("Mine.java", Set.of(ME));
        ledger.put("B.java", Set.of(JIYOUNG));
        RunPlanner planner = planner();
        RunPlanner.Attempt first = planner.first(Set.of(JIYOUNG), null, null);

        // 내 파일은 어떤 조합에서도 그대로 들어간다. 다시 빌드해도 같은 에러다.
        assertThat(planner.afterFailure(first, Set.of("Mine.java"))).isNull();
    }

    @Test
    @DisplayName("본인은 에러 파일의 작성자여도 제외되지 않는다")
    void neverExcludesMe() {
        ledger.put("Mine.java", Set.of(ME));
        ledger.put("B.java", Set.of(JIYOUNG));
        RunPlanner planner = planner();
        RunPlanner.Attempt first = planner.first(Set.of(JIYOUNG), null, null);

        RunPlanner.Attempt second = planner.afterFailure(first, Set.of("Mine.java", "B.java"));

        assertThat(second.included()).containsExactly(ME);
        assertThat(planner.afterFailure(second, Set.of("Mine.java")).included()).containsExactly(ME);
    }

    @Test
    @DisplayName("나와 다른 사람이 함께 고친 파일에서만 에러가 나면, 사람을 빼지 않고 에러와 함께 알린다")
    void sharedFileErrorStopsAndReportsCoAuthors() {
        ledger.put("Shared.java", Set.of(ME, JIYOUNG));
        ledger.put("B.java", Set.of(JIYOUNG));
        RunPlanner planner = planner();
        RunPlanner.Attempt first = planner.first(Set.of(JIYOUNG), null, null);

        // 지영을 빼도 Shared.java 는 내 변경이 들어 있어 되돌리지 않는다. 빌드해 봐야 같다.
        assertThat(planner.afterFailure(first, Set.of("Shared.java"))).isNull();

        assertThat(planner.sharedFiles(Set.of("Shared.java")))
                .containsExactly(new RunPlanner.SharedFile("Shared.java", Set.of(JIYOUNG)));
    }

    @Test
    @DisplayName("함께 고친 파일과 남의 파일에서 같이 에러가 나면, 남의 파일 작성자만 뺀다")
    void sharedFileDoesNotDecideWhoIsExcluded() {
        ledger.put("Shared.java", Set.of(ME, MINSU));
        ledger.put("B.java", Set.of(JIYOUNG));
        RunPlanner planner = planner();
        RunPlanner.Attempt first = planner.first(Set.of(MINSU, JIYOUNG), null, null);

        RunPlanner.Attempt second = planner.afterFailure(first, Set.of("Shared.java", "B.java"));

        // 민수는 Shared.java 를 함께 고쳤을 뿐이다. 그 파일은 되돌리지 않으므로 민수를
        // 뺄 이유가 없다.
        assertThat(second.included()).containsExactlyInAnyOrder(ME, MINSU);
        assertThat(planner.revertedFilesOf(JIYOUNG, second)).containsExactly("B.java");
    }

    @Test
    @DisplayName("제외된 사람의 파일이라도 나와 함께 고친 파일은 되돌린 목록에 없다")
    void revertedFilesSkipFilesSharedWithMe() {
        ledger.put("Shared.java", Set.of(ME, JIYOUNG));
        ledger.put("B.java", Set.of(JIYOUNG));
        RunPlanner planner = planner();
        RunPlanner.Attempt first = planner.first(Set.of(JIYOUNG), null, null);

        RunPlanner.Attempt second = planner.afterFailure(first, Set.of("B.java"));

        assertThat(planner.revertedFilesOf(JIYOUNG, second)).containsExactly("B.java");
    }

    @Test
    @DisplayName("에러 파일의 작성자가 장부에 없으면 빌드 2 를 건너뛰고 빌드 3 으로 간다")
    void unknownChangeSkipsToOwnOnly() {
        ledger.put("A.java", Set.of(MINSU));
        RunPlanner planner = planner();
        RunPlanner.Attempt first = planner.first(Set.of(MINSU), null, null);

        // 출처를 모르는 변경이다. 뺄 사람이 없으니 중간 단계가 의미 없다.
        RunPlanner.Attempt next = planner.afterFailure(first, Set.of("Unknown.java"));

        assertThat(next.number()).isEqualTo(2);
        assertThat(next.mode()).isEqualTo(RunPlanner.Mode.OWN_ONLY);
        assertThat(next.included()).containsExactly(ME);
        assertThat(next.includeUnknownChanges()).isFalse();
    }

    @Test
    @DisplayName("어느 파일인지 알아내지 못해도 빌드 3 으로 간다")
    void unparsedErrorSkipsToOwnOnly() {
        ledger.put("A.java", Set.of(MINSU));
        RunPlanner planner = planner();
        RunPlanner.Attempt first = planner.first(Set.of(MINSU), null, null);

        assertThat(planner.afterFailure(first, Set.of()).mode()).isEqualTo(RunPlanner.Mode.OWN_ONLY);
    }

    @Test
    @DisplayName("빌드 2 가 실패하면 빌드 3(green + 본인 변경만)으로 가고, 그 뒤는 없다")
    void fallsBackToOwnOnlyThenStops() {
        ledger.put("A.java", Set.of(MINSU));
        ledger.put("B.java", Set.of(JIYOUNG));
        RunPlanner planner = planner();
        RunPlanner.Attempt first = planner.first(Set.of(MINSU, JIYOUNG), null, null);
        RunPlanner.Attempt second = planner.afterFailure(first, Set.of("B.java"));

        RunPlanner.Attempt third = planner.afterFailure(second, Set.of("A.java"));

        assertThat(third.number()).isEqualTo(3);
        assertThat(third.mode()).isEqualTo(RunPlanner.Mode.OWN_ONLY);
        assertThat(third.included()).containsExactly(ME);
        assertThat(third.includeUnknownChanges()).isFalse();

        assertThat(planner.afterFailure(third, Set.of("A.java"))).isNull();
    }

    @Test
    @DisplayName("이미 제외된(수정 중인) 사람의 파일에서 에러가 나면 뺄 사람이 없으니 빌드 3 으로 간다")
    void alreadyExcludedAuthorIsNotACulprit() {
        ledger.put("B.java", Set.of(JIYOUNG));
        RunPlanner planner = planner();

        // 지영은 수정 중이라 처음부터 빠져 있다.
        RunPlanner.Attempt first = planner.first(Set.of(), null, null);

        assertThat(planner.afterFailure(first, Set.of("B.java")).mode()).isEqualTo(RunPlanner.Mode.OWN_ONLY);
    }

    @Test
    @DisplayName("빌드를 두 번만 허용하면 빌드 1 다음에 바로 green + 본인 변경으로 간다")
    void twoBuildLimitSkipsTheMiddleStep() {
        ledger.put("B.java", Set.of(JIYOUNG));
        RunPlanner planner = new RunPlanner(ledger, ME, true, 2);
        RunPlanner.Attempt first = planner.first(Set.of(JIYOUNG), null, null);

        RunPlanner.Attempt second = planner.afterFailure(first, Set.of("B.java"));

        assertThat(second.mode()).isEqualTo(RunPlanner.Mode.OWN_ONLY);
        assertThat(planner.afterFailure(second, Set.of("B.java"))).isNull();
    }

    @Test
    @DisplayName("정상 버전이 없으면 live 그대로 한 번만 빌드한다")
    void withoutGreenThereIsNoFallback() {
        ledger.put("B.java", Set.of(JIYOUNG));
        RunPlanner planner = new RunPlanner(ledger, ME, false, 3);

        // 수정 중인 사람도 뺄 수 없다. 되돌아갈 정상 버전이 없기 때문이다.
        RunPlanner.Attempt first = planner.first(Set.of(), null, Set.of(JIYOUNG));

        assertThat(first.mode()).isEqualTo(RunPlanner.Mode.NO_GREEN);
        assertThat(first.included()).containsExactlyInAnyOrder(ME, JIYOUNG);
        assertThat(planner.afterFailure(first, Set.of("B.java"))).isNull();
    }

    @Test
    @DisplayName("아무도 빠지지 않은 빌드 1 만 정상 버전으로 삼는다")
    void onlyFullBuildWithEveryoneIsPromotable() {
        ledger.put("A.java", Set.of(MINSU));
        ledger.put("B.java", Set.of(JIYOUNG));
        RunPlanner planner = planner();

        RunPlanner.Attempt everyone = planner.first(Set.of(MINSU, JIYOUNG), null, null);
        assertThat(planner.isPromotable(everyone)).isTrue();

        // 지영이 수정 중이라 빠진 조합. 이것을 green 으로 삼으면 지영의 변경이 장부에서 사라질 수 있다.
        RunPlanner.Attempt withoutJiyoung = planner.first(Set.of(MINSU), null, null);
        assertThat(planner.isPromotable(withoutJiyoung)).isFalse();

        RunPlanner.Attempt second = planner.afterFailure(everyone, Set.of("B.java"));
        assertThat(planner.isPromotable(second)).isFalse();

        RunPlanner.Attempt third = planner.afterFailure(second, Set.of("A.java"));
        assertThat(planner.isPromotable(third)).isFalse();
    }

    @Test
    @DisplayName("정상 버전이 없을 때의 첫 빌드는 성공하면 정상 버전이 된다")
    void firstEverBuildIsPromotable() {
        RunPlanner planner = new RunPlanner(ledger, ME, false, 3);

        assertThat(planner.isPromotable(planner.first(Set.of(), null, null))).isTrue();
    }
}
