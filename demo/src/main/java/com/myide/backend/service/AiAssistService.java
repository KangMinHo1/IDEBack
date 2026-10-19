package com.myide.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.aiassist.AiAssistRequest;
import com.myide.backend.dto.aiassist.AiAssistResponse;
import com.myide.backend.dto.codemap.CodeMapResponse;
import com.myide.backend.service.ai.ProjectContextExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAssistService {

    private final CodeMapService codeMapService;
    private final CoreAiService coreAiService; // 💡 공통 통신 모듈 주입!
    private final ObjectMapper objectMapper;
    private final ProjectContextExtractor projectContextExtractor;

    public AiAssistResponse getAiSuggestion(AiAssistRequest req) {
        try {
            // 💡 [수정됨] 4번째 인자로 "JAVA"를 명시적으로 추가하여 컴파일 에러 완벽 해결!
            CodeMapResponse contextMap = codeMapService.getAnalyzedCodeMap(
                    req.getWorkspaceId(), req.getProjectName(), req.getBranchName(), "JAVA");

            String projectContext = formatContext(contextMap);
            // 클래스 이름만으로는 AI 가 API 주소와 필드 이름을 짐작해서 썼다. 실제 주소·필드·의존성·
            // 실행 환경을 함께 넘겨 프로젝트에 맞는 코드를 쓰게 한다(ProjectContextExtractor).
            String projectFacts = projectContextExtractor.summarize(
                    req.getWorkspaceId(), req.getProjectName(), req.getBranchName());

            String prompt = String.format(
                    // 코드를 JSON 글자 안에 넣게 하면 Gemini 가 줄바꿈을 그대로 넣어 JSON 이 깨지거나(3번 중 2번),
                    // JSON 모드를 켜면 줄바꿈을 아예 빼 코드가 한 줄이 됐다(5번 중 4번). 코드 블록으로 받는다.
                    "You are a Senior Java Developer. Answer in exactly this format and nothing else:\n\n" +
                            "EXPLANATION:\n" +
                            "(초보자도 이해하기 쉽게 마크다운 불릿(-)으로 3문장 이내 핵심만, 한국어)\n\n" +
                            "CODE:\n" +
                            "```\n" +
                            "(Target File 의 수정된 전체 코드. 수정이 없거나 단순 질문이면 이 블록을 비워 둘 것)\n" +
                            "```\n\n" +
                            "### Project Context:\n%s\n\n" +
                            "### Project Facts (read from the actual source. When the code calls these APIs or uses these types, " +
                            "use exactly these paths, HTTP methods and field names. Do not invent endpoints or fields that are not listed. " +
                            "Use only the listed dependencies and the JDK standard library.):\n%s\n\n" +
                            "### Target File: %s\n\n### Current Code:\n%s\n\n### User Request: %s",
                    projectContext, projectFacts.isBlank() ? "없음" : projectFacts,
                    req.getFilePath(), req.getCurrentCode(), req.getUserQuery()
            );

            // 사용자가 화면 앞에서 기다리므로 생각 단계를 끈다(CoreAiService.generateText(prompt, true)).
            String rawResponse = coreAiService.generateText(prompt, true);
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
        Optional<AiAssistResponse> sections = parseSections(rawResponse);
        if (sections.isPresent()) {
            return sections.get();
        }

        // 형식을 안 지키고 예전처럼 JSON 으로 답한 경우.
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

    /**
     * "EXPLANATION: … CODE: ```…```" 형식을 읽는다. CODE: 가 없으면 비어 있다.
     *
     * 코드는 CODE: 뒤 첫 ``` 줄 다음부터 마지막 ``` 앞까지다. 첫 닫는 ``` 에서 끊지 않는 이유는
     * 코드 안(문자열·주석)에 ``` 가 들어 있어도 잘리지 않게 하기 위해서다.
     */
    static Optional<AiAssistResponse> parseSections(String raw) {
        if (raw == null) {
            return Optional.empty();
        }

        int codeAt = raw.indexOf("CODE:");
        if (codeAt < 0) {
            return Optional.empty();
        }

        String explanation = raw.substring(0, codeAt).trim().replaceFirst("^EXPLANATION:\\s*", "").trim();
        String code;
        int open = raw.indexOf("```", codeAt);
        if (open < 0) {
            code = raw.substring(codeAt + "CODE:".length()).trim();
        } else {
            int lineEnd = raw.indexOf('\n', open);
            int close = raw.lastIndexOf("```");
            if (lineEnd < 0 || close <= open) {
                code = "";
            } else {
                code = close > lineEnd ? raw.substring(lineEnd + 1, close) : "";
                code = code.replaceAll("\\s+$", "");
                if (!code.isEmpty()) {
                    code += "\n";
                }
            }
        }

        return Optional.of(AiAssistResponse.builder()
                .success(true)
                .explanation(explanation)
                .suggestedCode(code)
                .build());
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
            // 타자를 치는 동안 떠야 하는 제안이라 생각 단계를 끈다. 켜 두면 몇 초씩 늦게 떠 쓸모가 없었다.
            String aiRaw = coreAiService.generateText(prompt, true);
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