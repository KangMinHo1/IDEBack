// 경로: src/main/java/com/myide/backend/service/CoreAiService.java
package com.myide.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoreAiService {

    private final ObjectMapper objectMapper;

    @Value("${google.gemini.api-key}")
    private String apiKey;

    @Value("${google.gemini.url}")
    private String apiUrl;

    /**
     * 어떤 프롬프트든 넣으면 순수하게 Gemini의 텍스트 결과만 뱉어주는 범용 AI 호출 메서드
     */
    public String generateText(String prompt) throws HttpClientErrorException, Exception {
        return generateText(prompt, false);
    }

    /**
     * fast 면 Gemini 2.5 의 "생각(thinking)" 단계를 끈다.
     *
     * 기본값은 답하기 전에 먼저 생각하는데, 코드 작성 요청 하나에 생각 토큰이 1만 개 넘게 쌓여
     * 70초가 걸렸다(생각을 끄면 같은 요청이 11~14초). 사용자가 화면 앞에서 기다리는 요청에 쓴다.
     */
    public String generateText(String prompt, boolean fast) throws HttpClientErrorException, Exception {
        RestTemplate restTemplate = new RestTemplate();
        Map<String, Object> requestBody = fast
                ? Map.of(
                        "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                        "generationConfig", Map.of("thinkingConfig", Map.of("thinkingBudget", 0)))
                : Map.of(
                        "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
                );

        String urlWithKey = apiUrl + "?key=" + apiKey;
        String responseStr = restTemplate.postForObject(urlWithKey, requestBody, String.class);

        JsonNode root = objectMapper.readTree(responseStr);
        return root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
    }
}