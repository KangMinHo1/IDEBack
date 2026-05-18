package com.myide.backend.service.aireport;

import com.myide.backend.dto.aireport.FinalReportDraftRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class FinalReportPromptBuilder {

    public String buildInstructions() {
        return """
                너는 컴퓨터소프트웨어과 졸업작품 최종 보고서 초안을 작성하는 AI 보조자다.

                작성 규칙:
                1. 제공된 프로젝트 정보, 개발일지, 설계 문서만 사용한다.
                2. 제공되지 않은 기능, 기술, 구현 내용은 임의로 추가하지 않는다.
                3. 정보가 부족한 부분은 '추가 작성 필요'라고 표시한다.
                4. 문체는 대학생이 직접 작성한 졸업작품 보고서처럼 자연스럽고 명확하게 작성한다.
                5. 너무 과하게 전문적이거나 딱딱한 표현은 피한다.
                6. 문장은 길게 늘이지 않고 읽기 쉽게 작성한다.
                7. 각 항목은 번호와 제목을 붙여 구분한다.
                8. 출력은 마크다운 표가 아니라 일반 텍스트 보고서 형식으로 작성한다.
                9. 결과는 사용자가 바로 수정할 수 있는 초안 형태로 작성한다.

                보고서 구성:
                1. 프로젝트 개요
                2. 개발 목적
                3. 주요 기능
                4. 설계 내용
                5. 개발 진행 과정
                6. 개발 결과
                7. 향후 개선점
                """;
    }

    public String buildInput(String workspaceId, FinalReportDraftRequest request) {
        FinalReportDraftRequest.ProjectInfo project = request.getProject();

        return """
                아래 데이터를 바탕으로 졸업작품 최종 보고서 초안을 작성해줘.

                [워크스페이스 ID]
                %s

                [프로젝트 정보]
                프로젝트명: %s
                설명: %s
                구분: %s
                대표 언어: %s
                기술 스택: %s
                진행률: %s%%
                완료 일정: %s/%s개
                개발일지 수: %s개

                [개발일지 목록]
                %s

                [요구사항 정의]
                %s

                [API 명세]
                %s

                [ERD]
                %s

                [데이터 플로우]
                %s

                [작성 요청]
                위 자료를 바탕으로 최종 보고서 초안을 작성해줘.
                데이터가 부족한 부분은 내용을 지어내지 말고 '추가 작성 필요'라고 적어줘.
                """.formatted(
                safe(workspaceId),
                safe(project.getName()),
                safe(project.getDescription()),
                safe(project.getType()),
                safe(project.getLanguage()),
                joinList(project.getStack()),
                safeNumber(project.getProgress()),
                safeNumber(project.getDoneScheduleCount()),
                safeNumber(project.getScheduleTotalCount()),
                safeNumber(project.getDevlogCount()),
                buildDevlogText(request.getDevlogs()),
                buildRequirementText(request.getRequirements()),
                buildApiSpecText(request.getApiSpecs()),
                buildErdText(request.getErdTables()),
                buildFlowText(request.getFlowNodes())
        );
    }

    private String buildDevlogText(List<FinalReportDraftRequest.DevlogInfo> devlogs) {
        if (devlogs == null || devlogs.isEmpty()) {
            return "- 작성된 개발일지가 없습니다.";
        }

        return devlogs.stream()
                .limit(20)
                .map(devlog -> """
                        - 제목: %s
                          프로젝트: %s
                          작성일: %s
                          내용: %s
                        """.formatted(
                        safe(devlog.getTitle()),
                        safe(devlog.getProjectName()),
                        safe(devlog.getDate()),
                        safe(devlog.getSummary())
                ))
                .collect(Collectors.joining("\n"));
    }

    private String buildRequirementText(List<FinalReportDraftRequest.RequirementInfo> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return "- 작성된 요구사항이 없습니다.";
        }

        return requirements.stream()
                .limit(30)
                .map(requirement -> """
                        - 구분: %s
                          기능명: %s
                          설명: %s
                        """.formatted(
                        safe(requirement.getCategory()),
                        safe(requirement.getName()),
                        safe(requirement.getDescription())
                ))
                .collect(Collectors.joining("\n"));
    }

    private String buildApiSpecText(List<FinalReportDraftRequest.ApiSpecInfo> apiSpecs) {
        if (apiSpecs == null || apiSpecs.isEmpty()) {
            return "- 작성된 API 명세가 없습니다.";
        }

        return apiSpecs.stream()
                .limit(30)
                .map(api -> """
                        - %s %s
                          설명: %s
                          요청: %s
                          응답: %s
                        """.formatted(
                        safe(api.getMethod()),
                        safe(api.getEndpoint()),
                        safe(api.getDescription()),
                        safe(api.getRequest()),
                        safe(api.getResponse())
                ))
                .collect(Collectors.joining("\n"));
    }

    private String buildErdText(List<FinalReportDraftRequest.ErdTableInfo> erdTables) {
        if (erdTables == null || erdTables.isEmpty()) {
            return "- 작성된 ERD 테이블이 없습니다.";
        }

        return erdTables.stream()
                .limit(30)
                .map(table -> """
                        - 테이블명: %s
                          컬럼:
                        %s
                        """.formatted(
                        safe(table.getName()),
                        buildColumnText(table.getColumns())
                ))
                .collect(Collectors.joining("\n"));
    }

    private String buildColumnText(List<FinalReportDraftRequest.ErdColumnInfo> columns) {
        if (columns == null || columns.isEmpty()) {
            return "    - 컬럼 없음";
        }

        return columns.stream()
                .limit(40)
                .map(column -> "    - %s (%s)%s%s".formatted(
                        safe(column.getName()),
                        safe(column.getType()),
                        Boolean.TRUE.equals(column.getPk()) ? " PK" : "",
                        Boolean.TRUE.equals(column.getFk()) ? " FK" : ""
                ))
                .collect(Collectors.joining("\n"));
    }

    private String buildFlowText(List<FinalReportDraftRequest.FlowNodeInfo> flowNodes) {
        if (flowNodes == null || flowNodes.isEmpty()) {
            return "- 작성된 데이터 플로우가 없습니다.";
        }

        return flowNodes.stream()
                .limit(30)
                .map(node -> """
                        - 노드명: %s
                          유형: %s
                          설명/기술: %s
                        """.formatted(
                        safe(node.getLabel()),
                        safe(node.getType()),
                        safe(node.getTechStack())
                ))
                .collect(Collectors.joining("\n"));
    }

    private String safe(String value) {
        if (!StringUtils.hasText(value)) {
            return "추가 작성 필요";
        }

        return value.trim();
    }

    private String safeNumber(Integer value) {
        return value == null ? "0" : String.valueOf(value);
    }

    private String joinList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "추가 작성 필요";
        }

        String result = values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.joining(", "));

        return StringUtils.hasText(result) ? result : "추가 작성 필요";
    }
}