package com.myide.backend.service.aireport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.aireport.FinalReportDraftRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프롬프트를 다듬을 때 보고서 7개 항목과 사실 규칙이 빠지지 않는지 확인한다.
 * 자료실 화면과 PDF 출력이 이 항목 구성을 전제로 한다.
 */
class FinalReportPromptBuilderTest {

    private final FinalReportPromptBuilder builder = new FinalReportPromptBuilder();

    @Test
    @DisplayName("7개 항목을 순서대로 지시한다")
    void keepsSevenSectionsInOrder() {
        assertThat(builder.buildInstructions()).containsSubsequence(
                "1. 프로젝트 개요",
                "2. 개발 목적",
                "3. 주요 기능",
                "4. 설계 내용",
                "5. 개발 진행 과정",
                "6. 개발 결과",
                "7. 향후 개선점"
        );
    }

    @Test
    @DisplayName("글자 수 상한을 두지 않는다")
    void doesNotCapLength() {
        assertThat(builder.buildInstructions())
                .doesNotContainPattern("\\d+\\s*자\\s*(이내|이하)");
    }

    @Test
    @DisplayName("설계 연결을 구현 증거로 쓰지 말라고 지시한다")
    void separatesDesignFromImplementation() {
        assertThat(builder.buildInstructions())
                .contains("'설계 연결'(화면·API·테이블이 이어짐)은 설계가 끝났다는 뜻일 뿐, 구현의 증거가 아니다.");
    }

    @Test
    @DisplayName("기록이 없는 기능은 미구현으로 단정하지 말고 '구현 여부 확인 필요'로 쓰라고 지시한다")
    void hedgesUnrecordedFeatures() {
        assertThat(builder.buildInstructions())
                .contains("'구현 여부 확인 필요'라고 적는다")
                .contains("'미구현', '구현되지 않았다', '구현하지 못했다'로 단정하지 않는다");
    }

    @Test
    @DisplayName("기술 용어를 영문 그대로 쓰고 마크다운·코드 표시를 쓰지 말라고 지시한다")
    void keepsEnglishTermsAndPlainText() {
        assertThat(builder.buildInstructions())
                .contains("API, JWT, React, Spring Boot")
                .contains("MySQL")
                .contains("ERD")
                .contains("한글 발음으로 옮겨 쓰지 않는다")
                .contains("마크다운 기호(#, **, `, 표)를 쓰지 않는다")
                .contains("API 경로, 요청·응답 JSON, 컬럼 이름과 자료형을 나열하지 않는다");
    }

    @Test
    @DisplayName("저장된 설계가 없으면 요청 값을 기능 단위로 정리하고, 명세 원문은 넘기지 않는다")
    void legacyPathIsSummarized() throws Exception {
        FinalReportDraftRequest request = new ObjectMapper().readValue("""
                {"project":{"name":"P","stack":["Spring Boot"]},
                 "devlogs":[{"title":"로그인 구현","date":"2026-04-02","summary":"JWT 로그인을 만들었다."},
                            {"title":"기획","date":"2026-03-01","summary":"주제를 정했다."}],
                 "requirements":[{"category":"회원","name":"로그인","description":"이메일 로그인"},
                                 {"category":"알림","name":"푸시 알림","description":""}],
                 "apiSpecs":[{"method":"POST","endpoint":"/api/login","description":"로그인",
                              "request":"{\\"email\\":\\"x\\"}","response":"{\\"token\\":\\"y\\"}"}],
                 "erdTables":[{"name":"users","columns":[{"name":"id","type":"BIGINT","pk":true}]}]}
                """, FinalReportDraftRequest.class);

        ReportBrief brief = builder.buildBrief(request, null);

        assertThat(brief.input()).contains("■ 회원").contains("구현 근거: 개발일지에 구현 기록 있음");
        assertThat(brief.input()).doesNotContain("\"email\"").doesNotContain("BIGINT");
        // 개발일지는 날짜순으로 세운다.
        assertThat(brief.input()).containsSubsequence("2026-03-01 기획", "2026-04-02 로그인 구현");

        assertThat(brief.features()).extracting(ReportFeature::evidence)
                .isEqualTo(List.of(ReportFeature.Evidence.RECORDED, ReportFeature.Evidence.NONE));
    }
}
