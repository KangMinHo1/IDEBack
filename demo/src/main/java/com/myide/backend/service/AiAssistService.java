// 경로: src/main/java/com/myide/backend/service/AiAssistService.java
package com.myide.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.aiassist.AiAssistRequest;
import com.myide.backend.dto.aiassist.AiAssistResponse;
import com.myide.backend.dto.codemap.CodeMapResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAssistService {

    private final CodeMapService codeMapService;
    private final CoreAiService coreAiService; // 💡 공통 통신 모듈 주입!
    private final ObjectMapper objectMapper;

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

            // 💡 CoreAiService 호출
            String rawResponse = coreAiService.generateText(prompt);
            return parseSuggestionResponse(rawResponse, req.getCurrentCode());

        } catch (HttpClientErrorException e) {
            log.error("Google API 통신 오류! : {}", e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 429) {
                return buildErrorResponse("요청이 너무 빠르거나 할당량이 초과되었습니다. 잠시 후 다시 시도해 주세요! 😅", req.getCurrentCode());
            }
            return buildErrorResponse("AI 모델 호출 실패 (설정을 확인하세요): " + e.getStatusCode(), req.getCurrentCode());
        } catch (Exception e) {
            log.error("AI 어시스트 처리 중 알 수 없는 서버 오류", e);
            return buildErrorResponse("서버 내부 오류가 발생했습니다: " + e.getMessage(), req.getCurrentCode());
        }
    }

    private AiAssistResponse parseSuggestionResponse(String rawResponse, String originalCode) {
        try {
            String cleanJson = rawResponse.replaceAll("```json", "").replaceAll("```", "").trim();
            int startIndex = cleanJson.indexOf("{");
            int endIndex = cleanJson.lastIndexOf("}");
            if (startIndex != -1 && endIndex != -1) {
                cleanJson = cleanJson.substring(startIndex, endIndex + 1);
            }
            AiAssistResponse response = objectMapper.readValue(cleanJson, AiAssistResponse.class);
            response.setSuccess(true);
            return response;
        } catch (Exception e) {
            log.error("Gemini 응답 파싱 실패", e);
            return buildErrorResponse("AI가 올바른 형식으로 응답하지 않았습니다. 다시 시도해주세요.", originalCode);
        }
    }

    private AiAssistResponse buildErrorResponse(String message, String originalCode) {
        return AiAssistResponse.builder().success(false).explanation(message).suggestedCode(originalCode).build();
    }

    private String formatContext(CodeMapResponse map) {
        if (map == null || map.getNodes() == null) return "문맥 정보 없음";
        return map.getNodes().stream()
                .map(n -> String.format("- %s (%s)", n.getLabel(), n.getRole()))
                .collect(Collectors.joining("\n"));
    }

    public String getAutocompleteSuggestion(String prefix, String suffix) {
        String prompt = String.format(
                "You are an AI code completion engine. Return ONLY the exact code that should be inserted at the cursor position. " +
                        "NO markdown formatting like ```java. NO explanations. NO plain text. JUST the raw code.\n\n" +
                        "// Code BEFORE cursor:\n%s\n\n" +
                        "// Code AFTER cursor:\n%s", prefix, suffix
        );

        try {
            // 💡 CoreAiService 호출
            String aiRaw = coreAiService.generateText(prompt);
            return aiRaw.replaceAll("```[a-zA-Z]*\n", "").replaceAll("```", "").trim();
        } catch (HttpClientErrorException e) {
            log.error("💀 [고스트 텍스트 에러] 구글 API가 대답을 거절했습니다! 상태코드: {}", e.getStatusCode());
            return "";
        } catch (Exception e) {
            log.error("💀 [고스트 텍스트 에러] 구글 응답 파싱 에러", e);
            return "";
        }
    }
}