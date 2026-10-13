package com.myide.backend.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실패 이유를 제대로 남기는지 확인한다.
 *
 * 이 검사가 필요해진 이유가 있다. 키가 만료된 상태에서 초안 생성을 누르면
 * 화면에는 503 만 뜨고 서버 로그에는 아무것도 남지 않아, 무엇이 잘못됐는지
 * 알 방법이 없었다. Gemini 는 이유를 응답 본문에 정확히 적어 보내는데
 * 그걸 버리고 있었다.
 */
class GeminiHttpClientTest {

    private final GeminiHttpClient client = new GeminiHttpClient(new ObjectMapper());

    /** 키가 틀렸을 때 Gemini 가 실제로 돌려주는 응답. */
    private static final String KEY_INVALID = """
            {"error":{"code":400,"message":"API key not valid. Please pass a valid API key.",
            "status":"INVALID_ARGUMENT",
            "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo",
            "reason":"API_KEY_INVALID","domain":"googleapis.com"}]}}
            """;

    @Test
    @DisplayName("키가 틀리면 400 이라도 키 문제로 구분한다")
    void detectsInvalidKey() {
        assertThat(client.isKeyProblem(400, KEY_INVALID)).isTrue();
        assertThat(client.describeFailure(400, KEY_INVALID))
                .contains("google.gemini.api-key");
    }

    @Test
    @DisplayName("권한 오류도 키 문제로 본다")
    void detectsForbidden() {
        assertThat(client.isKeyProblem(403, "{}")).isTrue();
        assertThat(client.isKeyProblem(401, null)).isTrue();
    }

    @Test
    @DisplayName("키와 무관한 거부는 응답에 적힌 이유를 그대로 옮긴다")
    void keepsServerReason() {
        String body = """
                {"error":{"code":400,"message":"Invalid JSON payload received."}}
                """;

        assertThat(client.isKeyProblem(400, body)).isFalse();
        assertThat(client.describeFailure(400, body))
                .contains("400")
                .contains("Invalid JSON payload received.");
    }

    @Test
    @DisplayName("본문이 JSON 이 아니어도 이유를 남긴다")
    void survivesNonJsonBody() {
        assertThat(client.describeFailure(502, "<html>Bad Gateway</html>"))
                .contains("502")
                .contains("Bad Gateway");

        assertThat(client.describeFailure(500, null)).contains("500");
    }

    @Test
    @DisplayName("생각 단계를 끈 채로 요청한다")
    void disablesThinking() throws Exception {
        String body = client.buildRequestBody("안녕",
                GeminiHttpClient.GenerationOptions.json(1024, null));

        assertThat(new ObjectMapper().readTree(body)
                .path("generationConfig").path("thinkingConfig").path("thinkingBudget").asInt(-1))
                .isZero();
    }
}
