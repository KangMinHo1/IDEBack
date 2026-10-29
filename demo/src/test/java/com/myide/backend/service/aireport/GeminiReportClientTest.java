package com.myide.backend.service.aireport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보고서 요청 본문과 응답 처리를 확인한다.
 *
 * 생각 단계를 끄는 설정은 모델마다 다르고, 틀리면 400 으로 거부된다(실측).
 * 누가 모델을 바꾸다가 이 대응을 깨면 보고서가 아예 안 나오므로 여기서 잡는다.
 */
class GeminiReportClientTest {

    private static final String BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeminiReportClient client = new GeminiReportClient(objectMapper);

    @Test
    @DisplayName("보고서 전용 모델은 공통 URL 의 모델 이름만 바꿔 끼운다")
    void swapsModelOnly() {
        assertThat(GeminiReportClient.resolveUrl(BASE, "gemini-3.5-flash-lite"))
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent");
        assertThat(GeminiReportClient.resolveUrl(BASE, "")).isEqualTo(BASE);
        assertThat(GeminiReportClient.resolveUrl(BASE, null)).isEqualTo(BASE);
        assertThat(GeminiReportClient.modelOf(BASE)).isEqualTo("gemini-2.5-flash");
    }

    @Test
    @DisplayName("모델 계열마다 공식 지원되는 방식으로 생각 단계를 끈다")
    void thinkingConfigPerModel() {
        assertThat(GeminiReportClient.thinkingConfigFor("gemini-2.5-flash"))
                .isEqualTo(Map.of("thinkingBudget", 0));
        assertThat(GeminiReportClient.thinkingConfigFor("gemini-2.5-flash-lite"))
                .isEqualTo(Map.of("thinkingBudget", 0));
        assertThat(GeminiReportClient.thinkingConfigFor("gemini-2.5-pro"))
                .isEqualTo(Map.of("thinkingBudget", 128));
        assertThat(GeminiReportClient.thinkingConfigFor("gemini-3.5-flash-lite"))
                .isEqualTo(Map.of("thinkingLevel", "minimal"));
        assertThat(GeminiReportClient.thinkingConfigFor("gemini-3-flash-preview"))
                .isEqualTo(Map.of("thinkingLevel", "minimal"));
        assertThat(GeminiReportClient.thinkingConfigFor("gemini-3.1-pro"))
                .isEqualTo(Map.of("thinkingLevel", "low"));
        assertThat(GeminiReportClient.thinkingConfigFor("gemini-flash-latest")).isNull();
    }

    @Test
    @DisplayName("기존 생성 옵션은 그대로 두고 지침은 시스템 지시로 보낸다")
    void keepsOptionsAndSeparatesInstructions() {
        JsonNode body = objectMapper.valueToTree(
                client.buildRequestBody("지시", "자료", "gemini-2.5-flash"));
        JsonNode config = body.path("generationConfig");

        assertThat(config.path("thinkingConfig").path("thinkingBudget").asInt(-1)).isZero();
        assertThat(config.path("temperature").asDouble()).isEqualTo(0.35);
        assertThat(config.path("topP").asDouble()).isEqualTo(0.9);
        assertThat(config.path("maxOutputTokens").asInt()).isEqualTo(8192);

        assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asText()).isEqualTo("지시");
        assertThat(body.path("contents").get(0).path("parts").get(0).path("text").asText()).isEqualTo("자료");
    }

    @Test
    @DisplayName("3 계열 모델에는 thinkingBudget 대신 thinkingLevel 을 보낸다")
    void usesThinkingLevelForGemini3() {
        JsonNode config = objectMapper.valueToTree(
                client.buildRequestBody("지시", "자료", "gemini-3.5-flash-lite")).path("generationConfig");

        assertThat(config.path("thinkingConfig").has("thinkingBudget")).isFalse();
        assertThat(config.path("thinkingConfig").path("thinkingLevel").asText()).isEqualTo("minimal");
    }

    @Test
    @DisplayName("사용량과 종료 이유를 함께 꺼내고 생각 조각은 본문에서 뺀다")
    void parsesUsage() {
        String response = """
                {"candidates":[{"content":{"parts":[{"text":"생각","thought":true},{"text":"1. 프로젝트 개요"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":1200,"candidatesTokenCount":3000,"thoughtsTokenCount":5}}
                """;

        GeminiReportClient.ReportGeneration result = client.parse(response, "m", 10, 1);

        assertThat(result.text()).isEqualTo("1. 프로젝트 개요");
        assertThat(result.promptTokens()).isEqualTo(1200);
        assertThat(result.outputTokens()).isEqualTo(3000);
        assertThat(result.thoughtTokens()).isEqualTo(5);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("길이 제한에 걸리면 끊겼다는 알림을 붙인다")
    void marksTruncatedResponse() {
        String response = """
                {"candidates":[{"content":{"parts":[{"text":"1. 프로젝트 개요"}]},"finishReason":"MAX_TOKENS"}]}
                """;

        GeminiReportClient.ReportGeneration result = client.parse(response, "m", 10, 1);

        assertThat(result.text()).contains("[알림]");
        assertThat(result.truncated()).isTrue();
    }
}
