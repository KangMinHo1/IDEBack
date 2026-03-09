package com.myide.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.aiassist.AiAssistRequest;
import com.myide.backend.dto.aiassist.AiAssistResponse;
import com.myide.backend.dto.codemap.CodeMapResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAssistService {

    private final CodeMapService codeMapService;
    private final ObjectMapper objectMapper;

    @Value("${google.gemini.api-key}")
    private String apiKey;

    @Value("${google.gemini.url}")
    private String apiUrl;

    public AiAssistResponse getAiSuggestion(AiAssistRequest req) {
        try {
            CodeMapResponse contextMap = codeMapService.getAnalyzedCodeMap(
                    req.getWorkspaceId(), req.getProjectName(), req.getBranchName());
            String projectContext = formatContext(contextMap);

            String prompt = String.format(
                    "You are a Senior Java Developer. You MUST respond ONLY in valid JSON format. Do NOT add any markdown formatting like ```json or any plain text outside the JSON.\n\n" +
                            "【JSON FORMAT】\n" +
                            "{\n" +
                            "  \"explanation\": \"여기에 답변을 작성해. 단, 초보자도 이해하기 쉽게 마크다운 불릿(-)을 써서 3문장 이내로 핵심만 요약할 것.\",\n" +
                            "  \"suggestedCode\": \"수정된 전체 코드를 작성해. (수정이 없거나 단순 질문이면 빈 문자열로 둘 것)\"\n" +
                            "}\n\n" +
                            "### Project Context:\n%s\n\n### Target File: %s\n\n### Current Code:\n%s\n\n### User Request: %s",
                    projectContext, req.getFilePath(), req.getCurrentCode(), req.getUserQuery()
            );

            return callGemini(prompt, req.getCurrentCode());

        } catch (Exception e) {
            log.error("AI 어시스트 처리 중 알 수 없는 서버 오류", e);
            return AiAssistResponse.builder()
                    .success(false)
                    .explanation("서버 내부 오류가 발생했습니다: " + e.getMessage())
                    .suggestedCode(req.getCurrentCode())
                    .build();
        }
    }

    private AiAssistResponse callGemini(String prompt, String originalCode) {
        RestTemplate restTemplate = new RestTemplate();
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
        );

        try {
            String urlWithKey = apiUrl + "?key=" + apiKey;
            String responseStr = restTemplate.postForObject(urlWithKey, requestBody, String.class);

            JsonNode root = objectMapper.readTree(responseStr);
            String aiJsonRaw = root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();

            aiJsonRaw = aiJsonRaw.replaceAll("```json", "").replaceAll("```", "").trim();
            int startIndex = aiJsonRaw.indexOf("{");
            int endIndex = aiJsonRaw.lastIndexOf("}");
            if (startIndex != -1 && endIndex != -1) {
                aiJsonRaw = aiJsonRaw.substring(startIndex, endIndex + 1);
            }

            AiAssistResponse response = objectMapper.readValue(aiJsonRaw, AiAssistResponse.class);
            response.setSuccess(true);
            return response;

        } catch (HttpClientErrorException e) {
            log.error("Google API 통신 오류! : {}", e.getResponseBodyAsString());

            // 💡 429 에러(너무 잦은 요청) 방어 로직 포함
            if (e.getStatusCode().value() == 429) {
                return AiAssistResponse.builder()
                        .success(false)
                        .explanation("요청이 너무 빠르거나 할당량이 초과되었습니다. 잠시 후 다시 시도해 주세요! 😅")
                        .suggestedCode(originalCode)
                        .build();
            }

            return AiAssistResponse.builder()
                    .success(false)
                    .explanation("AI 모델 호출 실패 (설정을 확인하세요): " + e.getStatusCode())
                    .suggestedCode(originalCode)
                    .build();
        } catch (Exception e) {
            log.error("Gemini 응답 파싱 실패", e);
            return AiAssistResponse.builder()
                    .success(false)
                    .explanation("AI가 올바른 형식으로 응답하지 않았습니다. 다시 시도해주세요.")
                    .suggestedCode(originalCode)
                    .build();
        }
    }

    private String formatContext(CodeMapResponse map) {
        if (map == null || map.getNodes() == null) return "문맥 정보 없음";
        return map.getNodes().stream()
                .map(n -> String.format("- %s (%s)", n.getLabel(), n.getRole()))
                .collect(Collectors.joining("\n"));
    }

    // ====================================================================
    // 💡 고스트 텍스트(자동완성) 전용 메서드
    // ====================================================================
    public String getAutocompleteSuggestion(String prefix, String suffix) {
        String prompt = String.format(
                "You are an AI code completion engine. Return ONLY the exact code that should be inserted at the cursor position. " +
                        "NO markdown formatting like ```java. NO explanations. NO plain text. JUST the raw code.\n\n" +
                        "// Code BEFORE cursor:\n%s\n\n" +
                        "// Code AFTER cursor:\n%s", prefix, suffix
        );

        RestTemplate restTemplate = new RestTemplate();
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
        );

        try {
            String urlWithKey = apiUrl + "?key=" + apiKey;
            String responseStr = restTemplate.postForObject(urlWithKey, requestBody, String.class);
            JsonNode root = objectMapper.readTree(responseStr);
            String aiRaw = root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();

            aiRaw = aiRaw.replaceAll("```[a-zA-Z]*\n", "").replaceAll("```", "").trim();
            return aiRaw;

            // 🚨 [수정됨] 통신 에러 시 구글의 거절 사유를 상세하게 출력!
        } catch (HttpClientErrorException e) {
            log.error("💀 [고스트 텍스트 에러] 구글 API가 대답을 거절했습니다!");
            log.error("👉 거절 이유(상태코드): {}", e.getStatusCode());
            log.error("👉 상세 내용: {}", e.getResponseBodyAsString());
            return "";

            // 🚨 [수정됨] 파싱 에러 시 상세 원인 출력!
        } catch (Exception e) {
            log.error("💀 [고스트 텍스트 에러] 구글 응답은 왔는데 파싱하다 터졌습니다!");
            log.error("👉 에러 메시지: {}", e.getMessage(), e);
            return "";
        }
    }
}