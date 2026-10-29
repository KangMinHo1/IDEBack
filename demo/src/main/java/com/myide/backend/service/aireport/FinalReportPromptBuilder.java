package com.myide.backend.service.aireport;

import com.myide.backend.dto.aireport.FinalReportDraftRequest;
import com.myide.backend.dto.aireport.FinalReportDraftRequest.DevlogInfo;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class FinalReportPromptBuilder {

    /** 보고서 7개 항목. 자료실 화면과 PDF 출력이 이 구성을 전제로 하고, 응답 검사도 이걸 본다. */
    public static final List<String> SECTIONS = List.of(
            "프로젝트 개요",
            "개발 목적",
            "주요 기능",
            "설계 내용",
            "개발 진행 과정",
            "개발 결과",
            "향후 개선점"
    );

    /**
     * 보고서 작성 지침.
     *
     * 예전 지침은 "자료만 사용하라" 정도여서 AI 가 넘겨받은 명세를 항목마다 옮겨 적었다.
     * 지금은 자료가 기능별로 정리되어 오므로, 지침은 <b>어떻게 엮어 쓸지</b>와
     * <b>설계와 구현을 섞지 말 것</b>에 집중한다. 분량은 제한하지 않는다.
     */
    public String buildInstructions() {
        return """
                너는 대학 컴퓨터소프트웨어과 졸업작품 최종 보고서를 작성하는 조력자다.
                사용자가 주는 [보고서 자료]는 서버가 설계 문서와 개발일지를 기능별로 정리한 것이다.
                자료를 옮겨 적지 말고, 자료가 말해 주는 사실을 서로 엮어 사람이 쓴 보고서 문장으로 풀어 쓴다.

                [사실 규칙]
                1. 자료에 있는 사실만 쓴다. 자료에 없는 기능, 기술, 성능 수치, 사용자 수, 테스트 결과는 만들지 않는다.
                2. 어떤 기능을 "구현하였다"고 쓰려면 그 기능의 '구현 근거'에 개발일지 구현 기록이 있어야 한다.
                   - '설계 연결'(화면·API·테이블이 이어짐)은 설계가 끝났다는 뜻일 뿐, 구현의 증거가 아니다.
                   - 구현 근거가 '미완성'이면 진행 중이거나 일부만 구현된 기능으로 쓴다.
                   - 구현 근거가 '기록 없음'이면 '설계하였다'까지만 쓰고 '구현 여부 확인 필요'라고 적는다.
                     개발일지에 기록이 없다는 것은 확인하지 못했다는 뜻이지 만들지 않았다는 뜻이 아니므로,
                     '미구현', '구현되지 않았다', '구현하지 못했다'로 단정하지 않는다.
                     ('미완성'으로 기록된 기능은 일지에 적힌 대로 미완성이라고 쓴다.)
                3. 정보가 부족한 부분은 '추가 작성 필요'라고 적는다.

                [문체 규칙]
                4. 각 항목은 문단으로 서술한다. 한 문단은 하나의 주제를 여러 문장으로 이어서 설명한다.
                   목록은 꼭 필요한 곳에만 쓰고, 목록을 쓰더라도 각 줄을 완결된 문장으로 쓴다.
                5. API 경로, 요청·응답 JSON, 컬럼 이름과 자료형을 나열하지 않는다.
                   API 는 맡은 역할로, 테이블은 무엇을 저장하고 무엇과 연결되는지로 설명한다.
                6. 같은 사실을 여러 항목에서 반복하지 않는다. 앞에서 설명한 내용은 뒤에서 이름만 언급한다.
                7. "~하였다", "~이다"로 끝나는 보고서체로, 대학생이 직접 쓴 것처럼 자연스럽고 명확하게 쓴다.
                   과장 수식어, 예고 문장("~에 대해 설명한다"), 항목 끝의 요약 반복은 쓰지 않는다.
                8. 마크다운 기호(#, **, `, 표)를 쓰지 않는다. 항목 제목은 한 줄에 "1. 프로젝트 개요"처럼 쓰고,
                   필요하면 "3.1 회원 기능"처럼 하위 제목을 둔다. 테이블 이름도 따옴표나 ` 없이 그대로 쓴다.
                9. 기술 이름과 약어는 자료에 적힌 영문 표기 그대로 쓴다.
                   예: API, JWT, React, Spring Boot, Spring Security, MySQL, JPA, QueryDSL, ERD, Docker, WebSocket.
                   한글 발음으로 옮겨 쓰지 않는다(에이피아이, 제이더블유티, 리액트, 스프링 부트, 마이에스큐엘, 이알디 모두 금지).

                [항목별 작성 지침] 아래 7개 항목을 이 순서로 모두 쓴다.
                1. 프로젝트 개요: 무엇을 만드는 프로젝트인지, 누가 어떤 상황에서 쓰는지, 사용 기술과 진행 현황을 문단으로 쓴다.
                2. 개발 목적: 프로젝트 설명과 요구사항에서 드러나는 해결하려는 문제와 목표를 쓴다. 자료에 없는 사회적 배경은 지어내지 않는다.
                3. 주요 기능: 기능 분류별로 하위 제목을 두고, 기능마다 목적, 사용자가 화면에서 하는 일, 서버와 데이터가 맡는 역할을
                   하나의 흐름으로 이어지는 문단으로 설명한다. 필수 기능을 먼저, 가장 자세히 쓴다.
                4. 설계 내용: 시스템 구성(화면·서버·데이터베이스의 역할 분담), 핵심 사용자 흐름, API 구성 방식, 데이터 구조(중심 테이블과 관계)를
                   하위 제목으로 나누어 구성 요소 사이의 관계를 중심으로 쓴다. 설계 점검 결과는 수치를 근거로 짧게 평가한다.
                5. 개발 진행 과정: 개발일지를 시기별 단계(기획·설계, 핵심 기능 구현, 부가 기능, 마무리 등)로 묶어 시간 순서대로 쓴다.
                   일지에 어떤 문제를 어떻게 해결했는지가 있으면 포함한다.
                6. 개발 결과: 구현 근거가 있는 기능만 결과로 쓰고, 미완성 기능과 '구현 여부 확인 필요' 기능은 따로 구분해 밝힌다.
                   설계 완성도는 설계 점검 수치를 근거로 쓴다.
                7. 향후 개선점: 미완성 기능, 구현 여부 확인이 필요한 기능, 설계 점검의 경고와 끊긴 연결을 근거로 구체적인 다음 할 일을 쓴다.
                   근거 없는 일반론(성능 최적화, UI 개선 등)은 쓰지 않는다.
                """;
    }

    /**
     * 보고서 자료를 만든다.
     *
     * @param designBrief 서버가 저장된 설계에서 정리한 자료. null 이면 요청에 담겨 온 값을 정리해 쓴다.
     */
    public ReportBrief buildBrief(FinalReportDraftRequest request,
                                  DesignReportSectionBuilder.DesignBrief designBrief) {
        List<DevlogInfo> devlogs = selectDevlogs(request.getDevlogs());

        List<ReportFeature> features;
        String designText;

        if (designBrief != null) {
            features = designBrief.features();
            designText = designBrief.text();
        } else {
            features = new ArrayList<>();
            designText = buildLegacyDesignText(request, devlogs, features);
        }

        String input = """
                [보고서 자료]

                %s

                %s

                %s

                위 자료로 졸업작품 최종 보고서 7개 항목을 작성해줘.
                """.formatted(projectText(request.getProject()), devlogText(devlogs), designText);

        return new ReportBrief(input, features);
    }

    private String projectText(FinalReportDraftRequest.ProjectInfo project) {
        return """
                [프로젝트]
                이름: %s
                소개: %s
                구분: %s
                대표 언어: %s
                기술 스택: %s
                진행 현황(팀 입력값): 진행률 %s%%, 일정 %s/%s개 완료, 개발일지 %s개""".formatted(
                safe(project.getName()),
                safe(project.getDescription()),
                safe(project.getType()),
                safe(project.getLanguage()),
                joinList(project.getStack()),
                safeNumber(project.getProgress()),
                safeNumber(project.getDoneScheduleCount()),
                safeNumber(project.getScheduleTotalCount()),
                safeNumber(project.getDevlogCount())
        );
    }

    /** 개수 제한은 예전 그대로(20개)다. 고른 뒤 날짜순으로 세워 시간 흐름을 드러낸다. */
    private List<DevlogInfo> selectDevlogs(List<DevlogInfo> devlogs) {
        if (devlogs == null) {
            return List.of();
        }

        return devlogs.stream()
                .limit(20)
                .sorted(Comparator.comparing(devlog -> devlog.getDate() == null ? "" : devlog.getDate()))
                .toList();
    }

    private String devlogText(List<DevlogInfo> devlogs) {
        if (devlogs.isEmpty()) {
            return "[개발일지]\n- 작성된 개발일지가 없습니다.";
        }

        String first = devlogs.get(0).getDate();
        String last = devlogs.get(devlogs.size() - 1).getDate();

        StringBuilder builder = new StringBuilder("[개발일지] 날짜순");
        if (StringUtils.hasText(first) && StringUtils.hasText(last)) {
            builder.append(", ").append(first).append(" ~ ").append(last);
        }
        builder.append("\n");

        for (DevlogInfo devlog : devlogs) {
            builder.append("- ");
            if (StringUtils.hasText(devlog.getDate())) {
                builder.append(devlog.getDate().trim()).append(" ");
            }
            builder.append(safe(devlog.getTitle()));
            if (StringUtils.hasText(devlog.getSummary())) {
                builder.append(": ").append(DesignReportSectionBuilder.oneLine(devlog.getSummary()));
            }
            builder.append("\n");
        }

        return builder.toString().stripTrailing();
    }

    /**
     * 저장된 설계가 아직 없을 때만 쓰는 길.
     *
     * 새 설계 화면을 한 번도 열지 않은 워크스페이스가 있으므로 남겨 둔다. 이 경우에는
     * 설계 점검 결과와 기능-화면-API 연결을 알 수 없다. 그래도 저장본 경로와 같은
     * 원칙(기능 단위로 묶고, JSON·컬럼 자료형은 넘기지 않는다)으로 정리한다.
     */
    private String buildLegacyDesignText(FinalReportDraftRequest request, List<DevlogInfo> devlogs,
                                         List<ReportFeature> out) {
        StringBuilder builder = new StringBuilder();

        List<FinalReportDraftRequest.RequirementInfo> requirements =
                request.getRequirements() == null ? List.of() : request.getRequirements();

        builder.append("[기능별 정리] 요구사항 ").append(Math.min(requirements.size(), 30)).append("개\n");
        if (requirements.isEmpty()) {
            builder.append("- 작성된 요구사항이 없습니다.\n");
        }

        Map<String, List<FinalReportDraftRequest.RequirementInfo>> byCategory = new LinkedHashMap<>();
        requirements.stream().limit(30).forEach(requirement -> byCategory
                .computeIfAbsent(StringUtils.hasText(requirement.getCategory())
                        ? requirement.getCategory().trim() : "기타", key -> new ArrayList<>())
                .add(requirement));

        byCategory.forEach((category, items) -> {
            builder.append("■ ").append(category).append("\n");
            for (FinalReportDraftRequest.RequirementInfo requirement : items) {
                String name = safe(requirement.getName());
                DevlogEvidence.Match match = DevlogEvidence.find(requirement.getName(), devlogs);

                builder.append("- ").append(name).append("\n");
                if (StringUtils.hasText(requirement.getDescription())) {
                    builder.append("  내용: ").append(DesignReportSectionBuilder.oneLine(requirement.getDescription()))
                            .append("\n");
                }
                builder.append("  구현 근거: ").append(DesignReportSectionBuilder.describe(match)).append("\n");

                // 옛 요구사항에는 우선순위가 없어 모두 핵심 기능으로 본다.
                out.add(new ReportFeature(name, name, true, match.evidence()));
            }
        });

        List<FinalReportDraftRequest.ApiSpecInfo> apis =
                request.getApiSpecs() == null ? List.of() : request.getApiSpecs();
        builder.append("\n[API 구성]\n");
        if (apis.isEmpty()) {
            builder.append("- 작성된 API 명세가 없습니다.\n");
        } else {
            builder.append("총 ").append(Math.min(apis.size(), 30)).append("개. 역할: ")
                    .append(apis.stream().limit(30)
                            .map(api -> StringUtils.hasText(api.getDescription())
                                    ? DesignReportSectionBuilder.oneLine(api.getDescription())
                                    : safe(api.getMethod()) + " " + safe(api.getEndpoint()))
                            .distinct().collect(Collectors.joining(", ")))
                    .append("\n");
        }

        List<FinalReportDraftRequest.ErdTableInfo> tables =
                request.getErdTables() == null ? List.of() : request.getErdTables();
        builder.append("\n[데이터 구조]\n");
        if (tables.isEmpty()) {
            builder.append("- 작성된 ERD 테이블이 없습니다.\n");
        } else {
            tables.stream().limit(30).forEach(table -> {
                List<FinalReportDraftRequest.ErdColumnInfo> columns =
                        table.getColumns() == null ? List.of() : table.getColumns();
                long foreignKeys = columns.stream().filter(c -> Boolean.TRUE.equals(c.getFk())).count();
                builder.append("- ").append(safe(table.getName())).append(": 컬럼 ")
                        .append(columns.size()).append("개");
                if (foreignKeys > 0) {
                    builder.append(", 다른 테이블 참조 ").append(foreignKeys).append("개");
                }
                builder.append("\n");
            });
        }

        List<FinalReportDraftRequest.FlowNodeInfo> nodes =
                request.getFlowNodes() == null ? List.of() : request.getFlowNodes();
        builder.append("\n[시스템 구성(데이터 플로우)]\n");
        if (nodes.isEmpty()) {
            builder.append("- 작성된 데이터 플로우가 없습니다.\n");
        } else {
            nodes.stream().limit(30).forEach(node -> {
                builder.append("- ").append(safe(node.getLabel()));
                if (StringUtils.hasText(node.getType())) {
                    builder.append(" (").append(node.getType().trim()).append(")");
                }
                if (StringUtils.hasText(node.getTechStack())) {
                    builder.append(": ").append(node.getTechStack().trim());
                }
                builder.append("\n");
            });
        }

        builder.append("\n[설계 점검 결과]\n- 저장된 설계가 없어 점검 결과가 없습니다.\n");
        builder.append("\n[구현 근거 요약]\n").append(DesignReportSectionBuilder.evidenceSummary(out));

        return builder.toString().stripTrailing();
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
