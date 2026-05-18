package com.myide.backend.service.aireport;

import com.myide.backend.dto.aireport.FinalReportDraftRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class FinalReportService {

    private final FinalReportPromptBuilder promptBuilder;
    private final GeminiReportClient geminiReportClient;

    public String generateDraft(String workspaceId, FinalReportDraftRequest request) {
        validateRequest(workspaceId, request);

        String instructions = promptBuilder.buildInstructions();
        String input = promptBuilder.buildInput(workspaceId, request);

        return geminiReportClient.generateReport(instructions, input);
    }

    private void validateRequest(String workspaceId, FinalReportDraftRequest request) {
        if (!StringUtils.hasText(workspaceId)) {
            throw new IllegalArgumentException("workspaceId가 없습니다.");
        }

        if (request == null) {
            throw new IllegalArgumentException("최종 보고서 생성 요청 데이터가 없습니다.");
        }

        if (request.getProject() == null) {
            throw new IllegalArgumentException("프로젝트 정보가 없습니다.");
        }
    }
}