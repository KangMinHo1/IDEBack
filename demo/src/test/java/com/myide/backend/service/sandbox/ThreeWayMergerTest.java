package com.myide.backend.service.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 샌드박스를 반영할 때의 3-way 판정을 확인한다.
 *
 * 여기가 틀리면 팀원이 그 사이 고친 내용을 내 샌드박스가 덮어 버리거나(가장 나쁜 경우),
 * 합칠 수 있는 것을 충돌이라고 하게 된다.
 *
 * git 이 설치돼 있어야 돈다(서버는 이미 GitService 가 git 을 쓴다).
 */
class ThreeWayMergerTest {

    private static final String BASE = """
            line 1
            line 2
            line 3
            line 4
            line 5
            """;

    private final ThreeWayMerger merger = new ThreeWayMerger();

    @Test
    @DisplayName("팀 파일이 그대로면 내 것으로 바꾼다")
    void liveUntouchedTakesMine() {
        String mine = BASE.replace("line 2", "line 2 (mine)");

        ThreeWayMerger.Result result = merger.merge(BASE, mine, BASE);

        assertThat(result.outcome()).isEqualTo(ThreeWayMerger.Outcome.CLEAN);
        assertThat(result.content()).isEqualTo(mine);
    }

    @Test
    @DisplayName("내가 바꾼 것이 없으면 팀 파일을 건드리지 않는다")
    void nothingOfMineToApply() {
        String live = BASE.replace("line 4", "line 4 (team)");

        ThreeWayMerger.Result result = merger.merge(BASE, BASE, live);

        // 내 것을 그대로 넣으면 팀이 그 사이 고친 내용이 옛 내용으로 되돌아간다.
        assertThat(result.outcome()).isEqualTo(ThreeWayMerger.Outcome.UNCHANGED);
        assertThat(result.content()).isEqualTo(live);
    }

    @Test
    @DisplayName("둘이 같은 내용으로 고쳤으면 반영할 것이 없다")
    void sameChangeOnBothSides() {
        String changed = BASE.replace("line 3", "line 3 (same)");

        assertThat(merger.merge(BASE, changed, changed).outcome())
                .isEqualTo(ThreeWayMerger.Outcome.UNCHANGED);
    }

    @Test
    @DisplayName("서로 다른 곳을 고쳤으면 둘 다 살려서 합친다")
    void mergesNonOverlappingChanges() {
        String mine = BASE.replace("line 1", "line 1 (mine)");
        String live = BASE.replace("line 5", "line 5 (team)");

        ThreeWayMerger.Result result = merger.merge(BASE, mine, live);

        assertThat(result.outcome()).isEqualTo(ThreeWayMerger.Outcome.CLEAN);
        assertThat(result.content()).isEqualTo("""
                line 1 (mine)
                line 2
                line 3
                line 4
                line 5 (team)
                """);
    }

    @Test
    @DisplayName("같은 줄을 다르게 고쳤으면 충돌이고, 표식 위쪽이 내 것이다")
    void conflictingChanges() {
        String mine = BASE.replace("line 3", "line 3 (mine)");
        String live = BASE.replace("line 3", "line 3 (team)");

        ThreeWayMerger.Result result = merger.merge(BASE, mine, live);

        assertThat(result.outcome()).isEqualTo(ThreeWayMerger.Outcome.CONFLICT);

        // 에디터의 병합 충돌 화면이 이 표식을 그대로 읽는다.
        assertThat(result.content()).isEqualTo("""
                line 1
                line 2
                <<<<<<< sandbox
                line 3 (mine)
                =======
                line 3 (team)
                >>>>>>> team
                line 4
                line 5
                """);
    }

    @Test
    @DisplayName("충돌과 깨끗한 변경이 섞여 있으면 깨끗한 쪽은 합쳐진 채로 충돌만 표시한다")
    void conflictKeepsCleanHunks() {
        String mine = BASE.replace("line 1", "line 1 (mine)").replace("line 5", "line 5 (mine)");
        String live = BASE.replace("line 5", "line 5 (team)");

        ThreeWayMerger.Result result = merger.merge(BASE, mine, live);

        assertThat(result.outcome()).isEqualTo(ThreeWayMerger.Outcome.CONFLICT);
        assertThat(result.content())
                .startsWith("line 1 (mine)\n")
                .contains("<<<<<<< sandbox\nline 5 (mine)\n=======\nline 5 (team)\n>>>>>>> team\n");
    }

    @Test
    @DisplayName("양쪽이 같은 이름의 새 파일을 각자 만들었으면 빈 파일에서 출발한 것으로 본다")
    void bothCreatedTheFile() {
        ThreeWayMerger.Result result = merger.merge("", "mine\n", "team\n");

        assertThat(result.outcome()).isEqualTo(ThreeWayMerger.Outcome.CONFLICT);
        assertThat(result.content()).contains("<<<<<<< sandbox", "mine", "team", ">>>>>>> team");
    }

    @Test
    @DisplayName("한글과 줄바꿈 없는 끝줄도 그대로 보존한다")
    void preservesUnicodeAndMissingFinalNewline() {
        String base = "첫 줄\n둘째 줄\n셋째 줄";
        String mine = "첫 줄 (내 수정)\n둘째 줄\n셋째 줄";
        String live = "첫 줄\n둘째 줄\n셋째 줄 (팀 수정)";

        ThreeWayMerger.Result result = merger.merge(base, mine, live);

        assertThat(result.outcome()).isEqualTo(ThreeWayMerger.Outcome.CLEAN);
        assertThat(result.content()).isEqualTo("첫 줄 (내 수정)\n둘째 줄\n셋째 줄 (팀 수정)");
    }
}
