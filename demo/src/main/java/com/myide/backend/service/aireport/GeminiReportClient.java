package com.myide.backend.service.aireport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiReportClient {

    private final ObjectMapper objectMapper;

    @Value("${google.gemini.api-key:}")
    private String apiKey;

    @Value("${google.gemini.url:}")
    private String url;

    /**
     * 최종 보고서에만 쓰는 모델. 비어 있으면 공통 google.gemini.url 의 모델을 그대로 쓴다.
     *
     * 공통 URL 을 바꾸면 설계 초안·코드 도우미 같은 다른 AI 기능의 모델까지 바뀐다.
     * 그래서 URL 은 그대로 두고, 보고서 요청을 보낼 때만 경로의 모델 이름을 갈아 끼운다.
     */
    @Value("${google.gemini.report.model:}")
    private String reportModel;

    private static final int MAX_RETRY_COUNT = 3;

    /**
     * 보고서 한 편은 수 초~수십 초가 걸린다. 120초면 정상 생성이 잘리지 않으면서,
     * Gemini 가 아예 답하지 않을 때 요청 스레드가 무기한 묶이는 일은 막는다.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private static final Pattern MODEL_IN_URL = Pattern.compile("/models/([^/:]+):");

    /** 시도마다 새로 만들던 것을 하나로 재사용한다. 매번 TLS 연결을 새로 맺지 않는다. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** 생성 결과와 측정값. 본문 외에는 모두 로그와 성능 비교에 쓴다. */
    public record ReportGeneration(
            String text,
            String model,
            String finishReason,
            int promptTokens,
            int outputTokens,
            int thoughtTokens,
            long apiMillis,
            int attempts
    ) {
        public boolean truncated() {
            return "MAX_TOKENS".equals(finishReason);
        }
    }

    public String generateReport(String instructions, String input) {
        return generate(instructions, input).text();
    }

    public ReportGeneration generate(String instructions, String input) {
        return generate(instructions, input, reportModel);
    }

    /** modelOverride 가 비어 있으면 공통 URL 의 모델을 쓴다. 성능 비교에서 모델을 바꿔 볼 때도 쓴다. */
    ReportGeneration generate(String instructions, String input, String modelOverride) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "application-secret.yml에 google.gemini.api-key가 설정되지 않았습니다."
            );
        }

        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "application-secret.yml에 google.gemini.url이 설정되지 않았습니다."
            );
        }

        String targetUrl = resolveUrl(url, modelOverride);
        String model = modelOf(targetUrl);
        String requestBody;

        try {
            requestBody = objectMapper.writeValueAsString(
                    buildRequestBody(instructions, input, model)
            );
        } catch (IOException error) {
            throw new IllegalStateException("AI 요청 본문 생성에 실패했습니다.", error);
        }

        long totalStartedAt = System.nanoTime();

        for (int attempt = 1; attempt <= MAX_RETRY_COUNT; attempt++) {
            long startedAt = System.nanoTime();

            try {
                HttpResponse<String> response = sendRequest(targetUrl, requestBody);
                int statusCode = response.statusCode();

                log.info("⏱️ [보고서 AI] model={} {}회차 응답 status={} {}ms",
                        model, attempt, statusCode, elapsedMillis(startedAt));

                if (statusCode >= 200 && statusCode < 300) {
                    return parse(response.body(), model, elapsedMillis(totalStartedAt), attempt);
                }

                if (shouldRetry(statusCode) && attempt < MAX_RETRY_COUNT) {
                    sleepBeforeRetry(attempt);
                    continue;
                }

                throw new IllegalStateException(
                        "Gemini API 호출 실패: " + statusCode + " / " + response.body()
                );
            } catch (IOException error) {
                // 타임아웃(HttpTimeoutException)도 IOException 이라 기존 정책대로 재시도한다.
                log.warn("⚠️ [보고서 AI] {}회차 전송 오류 {}ms: {}",
                        attempt, elapsedMillis(startedAt), error.getClass().getSimpleName());

                if (attempt < MAX_RETRY_COUNT) {
                    sleepBeforeRetry(attempt);
                    continue;
                }

                throw new IllegalStateException("AI 요청 전송에 실패했습니다.", error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("AI 요청이 중단되었습니다.", error);
            }
        }

        throw new IllegalStateException("Gemini API 호출에 실패했습니다.");
    }

    private HttpResponse<String> sendRequest(String targetUrl, String requestBody)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(buildRequestUrl(targetUrl)))
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private boolean shouldRetry(int statusCode) {
        return statusCode == 429 || statusCode == 503 || statusCode == 500 || statusCode == 502 || statusCode == 504;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            long delayMillis = switch (attempt) {
                case 1 -> 1000L;
                case 2 -> 2000L;
                default -> 4000L;
            };

            Thread.sleep(delayMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 재시도 대기 중 요청이 중단되었습니다.", error);
        }
    }

    private String buildRequestUrl(String targetUrl) {
        String encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        if (targetUrl.contains("?")) {
            return targetUrl + "&key=" + encodedKey;
        }

        return targetUrl + "?key=" + encodedKey;
    }

    static String resolveUrl(String baseUrl, String model) {
        if (!StringUtils.hasText(model)) {
            return baseUrl;
        }

        Matcher matcher = MODEL_IN_URL.matcher(baseUrl);
        if (!matcher.find()) {
            log.warn("⚠️ [보고서 AI] google.gemini.url 에서 모델 위치를 찾지 못해 공통 모델을 씁니다.");
            return baseUrl;
        }

        return baseUrl.substring(0, matcher.start(1)) + model.trim() + baseUrl.substring(matcher.end(1));
    }

    static String modelOf(String targetUrl) {
        Matcher matcher = MODEL_IN_URL.matcher(targetUrl);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * 모델마다 "생각 단계"를 끄는 방법이 다르다. 잘못 보내면 400 으로 거부된다(실측).
     *  - 2.5 Flash / Flash-Lite : thinkingBudget 0 으로 끈다. (2.5 Pro 는 0 을 받지 않아 최솟값 128)
     *  - 3 계열                 : thinkingBudget 대신 thinkingLevel 을 쓴다. Flash 계열은 "minimal" 이 가장 낮고,
     *                             Pro 는 minimal 을 받지 않아 "low".
     * 보고서는 주어진 자료를 정리해 쓰는 일이라 깊은 추론이 필요 없고, 생각 토큰은
     * maxOutputTokens 안에서 쓰여 본문을 잘리게 만든다. 모르는 모델이면 아무것도 보내지 않는다.
     */
    static Map<String, Object> thinkingConfigFor(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }

        boolean pro = model.contains("-pro");

        if (model.startsWith("gemini-2.5")) {
            return Map.of("thinkingBudget", pro ? 128 : 0);
        }

        if (model.matches("gemini-[3-9](\\.\\d+)?-.*")) {
            return Map.of("thinkingLevel", pro ? "low" : "minimal");
        }

        return null;
    }

    Map<String, Object> buildRequestBody(String instructions, String input, String model) {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", input);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "user");
        content.put("parts", List.of(textPart));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0.35);
        generationConfig.put("topP", 0.9);
        generationConfig.put("maxOutputTokens", 8192);

        Map<String, Object> thinkingConfig = thinkingConfigFor(model);
        if (thinkingConfig != null) {
            generationConfig.put("thinkingConfig", thinkingConfig);
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();

        // 작성 지침은 시스템 지시로 따로 보낸다. 자료와 섞여 있으면 모델이 지침의
        // 예시 문장까지 자료로 읽어 옮겨 적는 일이 있었다.
        if (StringUtils.hasText(instructions)) {
            requestBody.put("systemInstruction", Map.of("parts", List.of(Map.of("text", instructions))));
        }

        requestBody.put("contents", List.of(content));
        requestBody.put("generationConfig", generationConfig);

        return requestBody;
    }

    ReportGeneration parse(String rawResponse, String model, long apiMillis, int attempts) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);

            JsonNode candidates = root.get("candidates");

            if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
                throw new IllegalStateException("Gemini 응답에 candidates가 없습니다.");
            }

            JsonNode firstCandidate = candidates.get(0);

            String finishReason = firstCandidate.has("finishReason")
                    ? firstCandidate.get("finishReason").asText()
                    : "";

            JsonNode usage = root.path("usageMetadata");
            int promptTokens = usage.path("promptTokenCount").asInt(0);
            int outputTokens = usage.path("candidatesTokenCount").asInt(0);
            int thoughtTokens = usage.path("thoughtsTokenCount").asInt(0);

            // 토큰 사용량만 남긴다. 본문은 개인정보가 섞일 수 있어 남기지 않는다.
            // 생각 토큰이 0 이 아니면 생각 단계가 다시 켜진 것이다.
            log.info("📊 [보고서 AI] model={} 토큰 입력={} 출력={} 생각={} finishReason={}",
                    model, promptTokens, outputTokens, thoughtTokens, finishReason);

            JsonNode content = firstCandidate.get("content");

            if (content == null) {
                throw new IllegalStateException(
                        "Gemini 응답에 content가 없습니다. finishReason=" + finishReason
                );
            }

            JsonNode parts = content.get("parts");

            if (parts == null || !parts.isArray() || parts.isEmpty()) {
                throw new IllegalStateException(
                        "Gemini 응답에 parts가 없습니다. finishReason=" + finishReason
                );
            }

            StringBuilder builder = new StringBuilder();

            for (JsonNode part : parts) {
                // 3 계열은 생각 요약 조각(thought=true)을 함께 보낼 수 있다. 본문이 아니다.
                if (part.path("thought").asBoolean(false)) {
                    continue;
                }

                JsonNode textNode = part.get("text");

                if (textNode != null && textNode.isTextual()) {
                    builder.append(textNode.asText()).append("\n");
                }
            }

            String result = builder.toString().trim();

            if (result.isBlank()) {
                throw new IllegalStateException(
                        "Gemini 응답에서 보고서 초안을 찾지 못했습니다. finishReason=" + finishReason
                );
            }

            if ("MAX_TOKENS".equals(finishReason)) {
                result += "\n\n[알림] AI 응답이 길이 제한으로 중간에 끊겼습니다. 다시 생성하거나 입력 자료를 줄여주세요.";
            }

            return new ReportGeneration(result, model, finishReason, promptTokens, outputTokens,
                    thoughtTokens, apiMillis, attempts);
        } catch (Exception error) {
            throw new IllegalStateException("Gemini 응답 처리에 실패했습니다: " + error.getMessage(), error);
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
