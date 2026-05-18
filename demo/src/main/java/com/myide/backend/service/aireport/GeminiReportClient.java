package com.myide.backend.service.aireport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GeminiReportClient {

    private final ObjectMapper objectMapper;

    @Value("${google.gemini.api-key:}")
    private String apiKey;

    @Value("${google.gemini.url:}")
    private String url;

    private static final int MAX_RETRY_COUNT = 3;

    public String generateReport(String instructions, String input) {
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

        String requestBody;

        try {
            requestBody = objectMapper.writeValueAsString(
                    buildRequestBody(instructions, input)
            );
        } catch (IOException error) {
            throw new IllegalStateException("AI 요청 본문 생성에 실패했습니다.", error);
        }

        for (int attempt = 1; attempt <= MAX_RETRY_COUNT; attempt++) {
            try {
                HttpResponse<String> response = sendRequest(requestBody);
                int statusCode = response.statusCode();

                if (statusCode >= 200 && statusCode < 300) {
                    return extractText(response.body());
                }

                if (shouldRetry(statusCode) && attempt < MAX_RETRY_COUNT) {
                    sleepBeforeRetry(attempt);
                    continue;
                }

                throw new IllegalStateException(
                        "Gemini API 호출 실패: " + statusCode + " / " + response.body()
                );
            } catch (IOException error) {
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

    private HttpResponse<String> sendRequest(String requestBody)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(buildRequestUrl()))
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    private String buildRequestUrl() {
        String encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        if (url.contains("?")) {
            return url + "&key=" + encodedKey;
        }

        return url + "?key=" + encodedKey;
    }

    private Map<String, Object> buildRequestBody(String instructions, String input) {
        String prompt = instructions + "\n\n" + input;

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", prompt);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "user");
        content.put("parts", List.of(textPart));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0.35);
        generationConfig.put("topP", 0.9);
        generationConfig.put("maxOutputTokens", 8192);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("contents", List.of(content));
        requestBody.put("generationConfig", generationConfig);

        return requestBody;
    }

    private String extractText(String rawResponse) {
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

            return result;
        } catch (Exception error) {
            throw new IllegalStateException("Gemini 응답 처리에 실패했습니다: " + error.getMessage(), error);
        }
    }
}