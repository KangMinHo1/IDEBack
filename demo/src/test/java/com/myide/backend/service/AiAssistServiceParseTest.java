package com.myide.backend.service;

import com.myide.backend.dto.aiassist.AiAssistResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiAssistServiceParseTest {

    @Test
    void 설명과_코드_블록을_나눈다() {
        String raw = "EXPLANATION:\n- 학생 목록을 보여 줍니다.\n- 등록할 수 있습니다.\n\n"
                + "CODE:\n```java\npackage a;\n\n// 주석: \"따옴표\" 와 \\n 도 그대로\npublic class A {\n}\n```\n";

        AiAssistResponse response = AiAssistService.parseSections(raw).orElseThrow();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getExplanation()).isEqualTo("- 학생 목록을 보여 줍니다.\n- 등록할 수 있습니다.");
        assertThat(response.getSuggestedCode())
                .isEqualTo("package a;\n\n// 주석: \"따옴표\" 와 \\n 도 그대로\npublic class A {\n}\n");
    }

    @Test
    void 코드_안의_백틱_세_개에서_끊지_않는다() {
        String raw = "EXPLANATION:\n- 설명\nCODE:\n```\nString md = \"```\";\n```";

        assertThat(AiAssistService.parseSections(raw).orElseThrow().getSuggestedCode())
                .isEqualTo("String md = \"```\";\n");
    }

    @Test
    void 코드_블록이_비면_코드는_빈_문자열() {
        String raw = "EXPLANATION:\n- 단순 질문에 대한 답입니다.\n\nCODE:\n```\n```";

        AiAssistResponse response = AiAssistService.parseSections(raw).orElseThrow();

        assertThat(response.getSuggestedCode()).isEmpty();
        assertThat(response.getExplanation()).isEqualTo("- 단순 질문에 대한 답입니다.");
    }

    @Test
    void 형식이_없으면_비어_있다() {
        // 예전 JSON 응답은 이전 파서가 읽는다.
        assertThat(AiAssistService.parseSections("{\"explanation\":\"x\",\"suggestedCode\":\"\"}")).isEmpty();
        assertThat(AiAssistService.parseSections(null)).isEmpty();
    }
}
