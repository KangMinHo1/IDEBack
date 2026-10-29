package com.myide.backend.service.aireport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinalReportValidatorTest {

    private final FinalReportValidator validator = new FinalReportValidator();

    private static final List<ReportFeature> FEATURES = List.of(
            new ReportFeature("R-01 로그인", "로그인", true, ReportFeature.Evidence.RECORDED),
            new ReportFeature("R-08 실시간 채팅", "실시간 채팅", false, ReportFeature.Evidence.NONE)
    );

    private static final String GOOD = """
            1. 프로젝트 개요
            이 프로젝트는 학생들이 스터디를 찾는 웹 서비스이다. 사용자는 로그인한 뒤 스터디를 둘러본다.

            2. 개발 목적
            흩어진 모집 글을 한곳에 모으는 것이 목표이다.

            3. 주요 기능
            3.1 회원 기능
            로그인은 이메일과 비밀번호로 이루어지며 서버가 토큰을 발급한다.

            4. 설계 내용
            화면, 서버, 데이터베이스가 역할을 나누어 맡는다.

            5. 개발 진행 과정
            3월에 설계를 마치고 4월부터 핵심 기능을 구현하였다.

            6. 개발 결과
            로그인 기능을 구현하였다. 채팅은 설계만 마쳤으며 구현 여부는 추가 작성 필요이다.

            7. 향후 개선점
            채팅 기능을 구현할 필요가 있다.
            """;

    @Test
    @DisplayName("문단형으로 7개 항목을 다 쓰면 통과한다")
    void passesGoodReport() {
        FinalReportValidator.Result result = validator.check(GOOD, FEATURES, false);

        assertThat(result.structureOk()).isTrue();
        assertThat(result.qualityOk()).isTrue();
        assertThat(result.proseRatio()).isGreaterThan(0.9);
    }

    @Test
    @DisplayName("마크다운 제목도 항목으로 인정한다")
    void acceptsMarkdownHeadings() {
        String markdown = GOOD.replace("1. 프로젝트 개요", "### 1. 프로젝트 개요")
                .replace("2. 개발 목적", "**2. 개발 목적**");

        assertThat(validator.check(markdown, FEATURES, false).missingSections()).isEmpty();
    }

    @Test
    @DisplayName("빠진 항목은 찾아서 빈 항목으로 붙인다")
    void completesMissingSection() {
        String withoutLast = GOOD.substring(0, GOOD.indexOf("7. 향후 개선점"));

        FinalReportValidator.Result result = validator.check(withoutLast, FEATURES, false);
        String completed = validator.completeSections(withoutLast, result);

        assertThat(result.missingSections()).containsExactly("향후 개선점");
        assertThat(completed).endsWith("7. 향후 개선점\n추가 작성 필요 (AI 응답에서 이 항목이 빠져 비워 두었습니다.)");
        assertThat(validator.check(completed, FEATURES, false).missingSections()).isEmpty();
    }

    @Test
    @DisplayName("API 명세와 컬럼을 줄줄이 옮기면 걸린다")
    void detectsSpecCopy() {
        String copied = GOOD.replace("화면, 서버, 데이터베이스가 역할을 나누어 맡는다.", """
                - POST /api/auth/login 로그인
                - GET /api/studies 목록
                - users.id BIGINT PK
                - users.email VARCHAR(100)
                - 요청: {"email":"string"}
                """);

        FinalReportValidator.Result result = validator.check(copied, FEATURES, false);

        assertThat(result.specCopyLines()).isEqualTo(5);
        assertThat(result.qualityOk()).isFalse();
    }

    @Test
    @DisplayName("개발일지 근거가 없는 기능을 구현했다고 쓰면 걸린다")
    void detectsUnsupportedClaim() {
        String claimed = GOOD.replace("채팅은 설계만 마쳤으며 구현 여부는 추가 작성 필요이다.",
                "실시간 채팅 기능도 설계에 따라 구현하였다.");

        FinalReportValidator.Result result = validator.check(claimed, FEATURES, false);

        assertThat(result.unsupportedClaims()).containsExactly("R-08 실시간 채팅");
    }

    @Test
    @DisplayName("기록이 없을 뿐인 기능을 미구현으로 단정하면 걸리고, '구현 여부 확인 필요'는 통과한다")
    void detectsDenialWithoutEvidence() {
        String denied = GOOD.replace("채팅은 설계만 마쳤으며 구현 여부는 추가 작성 필요이다.",
                "실시간 채팅 기능은 구현되지 않은 상태이다.");
        String hedged = GOOD.replace("채팅은 설계만 마쳤으며 구현 여부는 추가 작성 필요이다.",
                "실시간 채팅 기능은 설계되었으며 구현 여부 확인 필요이다.");

        assertThat(validator.check(denied, FEATURES, false).deniedWithoutEvidence())
                .containsExactly("R-08 실시간 채팅");
        assertThat(validator.check(hedged, FEATURES, false).deniedWithoutEvidence()).isEmpty();
    }

    @Test
    @DisplayName("영문 기술 용어를 한글 발음으로 옮겨 쓰면 걸린다")
    void detectsTransliteration() {
        String transliterated = GOOD.replace("서버가 토큰을 발급한다.",
                "서버가 제이더블유티를 발급하고 리액트 화면이 에이피아이를 호출한다. 에이알디도 설계하였다.");

        FinalReportValidator.Result result = validator.check(transliterated, FEATURES, false);

        assertThat(result.transliteratedTerms()).containsExactly("제이더블유티", "리액트", "에이피아이", "에이알디");
        assertThat(result.qualityOk()).isFalse();
        assertThat(validator.check(GOOD.replace("토큰을", "JWT 를"), FEATURES, false).transliteratedTerms()).isEmpty();
    }

    @Test
    @DisplayName("마크다운 제목·굵게·코드 표시를 센다")
    void countsMarkdown() {
        String markdown = GOOD.replace("1. 프로젝트 개요", "### 1. 프로젝트 개요")
                .replace("로그인은 이메일과", "**로그인**은 `users` 테이블의 이메일과");

        // 제목 1 + 굵게 여닫음 2 + 코드 표시 여닫음 2
        assertThat(validator.check(markdown, FEATURES, false).markdownMarks()).isEqualTo(5);
        assertThat(validator.check(GOOD, FEATURES, false).markdownMarks()).isZero();
    }

    @Test
    @DisplayName("필수 기능이 본문에 한 번도 나오지 않으면 걸린다")
    void detectsMissingKeyFeature() {
        String noLogin = GOOD.replace("로그인", "접속");

        assertThat(validator.check(noLogin, FEATURES, false).missingKeyFeatures())
                .containsExactly("R-01 로그인");
    }

    @Test
    @DisplayName("목록만 늘어놓으면 문단 비율이 낮다")
    void detectsListOnly() {
        String listy = GOOD.lines()
                .map(line -> line.isBlank() || line.matches("^\\d.*") ? line : "- " + line)
                .reduce("", (a, b) -> a + b + "\n");

        assertThat(validator.check(listy, FEATURES, false).proseRatio()).isLessThan(0.5);
    }
}
