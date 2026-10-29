package com.myide.backend.service.aireport;

import com.myide.backend.dto.aireport.FinalReportDraftRequest.DevlogInfo;
import com.myide.backend.dto.design.v2.ApiSpecV2;
import com.myide.backend.dto.design.v2.ColumnV2;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.DoctorReport;
import com.myide.backend.dto.design.v2.RelationV2;
import com.myide.backend.dto.design.v2.RequirementV2;
import com.myide.backend.dto.design.v2.ScreenTransitionV2;
import com.myide.backend.dto.design.v2.ScreenV2;
import com.myide.backend.dto.design.v2.TableV2;
import com.myide.backend.dto.design.v2.TechStackV2;
import com.myide.backend.repository.design.DesignDocSnapshotRepository;
import com.myide.backend.repository.design.DesignSnapshotVersion;
import com.myide.backend.service.design.DesignModelCodec;
import com.myide.backend.service.design.doctor.DesignDoctorService;
import com.myide.backend.service.design.doctor.Finding;
import com.myide.backend.service.design.doctor.Severity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 최종 보고서에 넣을 설계 자료를 서버가 직접 정리한다.
 *
 * 예전에는 화면이 설계 데이터를 통째로 담아 보냈고, 그다음 판에서는 서버가 저장본을
 * 읽되 API 요청·응답 JSON 과 ERD 컬럼까지 원문 그대로 넘겼다. 그러자 AI 가 그걸
 * 그대로 옮겨 적어 보고서가 명세서 사본이 됐고, 입력과 출력이 길어 느렸다.
 *
 * 지금은 <b>기능 단위로 묶어</b> 넘긴다. 요구사항 하나에 그 기능의 화면, API 의 역할,
 * 쓰는 테이블, 설계 연결 상태, 개발일지의 구현 근거를 모은다. 경로·JSON·컬럼
 * 자료형처럼 보고서에 옮겨 적으면 안 되는 세부는 처음부터 넣지 않는다.
 *
 * 저장된 설계가 없으면 null 을 돌려주고, 그때는 요청에 담겨 온 값을 쓴다.
 * 아직 새 설계 화면을 한 번도 열지 않은 워크스페이스가 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DesignReportSectionBuilder {

    /** 흐름은 대표 경로 몇 개면 충분하다. 모든 경로를 적으면 화면 전환 목록과 다를 게 없다. */
    private static final int MAX_FLOWS = 5;
    private static final int MAX_PATH_SEARCH = 300;
    private static final int MAX_KEY_COLUMNS = 4;
    private static final int MAX_FINDING_GROUPS = 10;

    private static final Pattern AUDIT_COLUMN =
            Pattern.compile("^(id|.*_id|created_at|updated_at|deleted_at)$");

    private final DesignDocSnapshotRepository snapshotRepository;
    private final DesignModelCodec codec;
    private final DesignDoctorService doctorService;

    /** 설계 자료 글과, 응답 검사에 다시 쓸 기능 목록. */
    public record DesignBrief(String text, List<ReportFeature> features) {
    }

    /** 캐시 키에 쓸 저장본 버전. 큰 칸은 읽지 않는다. */
    public Optional<DesignSnapshotVersion> currentVersion(String workspaceId) {
        return snapshotRepository.findVersionByWorkspaceUuid(workspaceId);
    }

    public DesignBrief build(String workspaceId, List<DevlogInfo> devlogs) {
        Optional<String> projectionJson = snapshotRepository.findProjectionJsonByWorkspaceUuid(workspaceId);

        if (projectionJson.isEmpty()) {
            return null;
        }

        DesignModelV2 model;

        try {
            model = codec.fromJson(projectionJson.get());
        } catch (Exception e) {
            log.warn("⚠️ [보고서] 저장된 설계를 읽지 못해 요청 값으로 대신합니다: {}", e.getMessage());
            return null;
        }

        return build(model, devlogs);
    }

    DesignBrief build(DesignModelV2 model, List<DevlogInfo> devlogs) {
        if (model.requirements().isEmpty() && model.screens().isEmpty()
                && model.apis().isEmpty() && model.erd().tables().isEmpty()) {
            return null;
        }

        Names names = new Names(model);
        List<ReportFeature> features = new ArrayList<>();
        StringBuilder builder = new StringBuilder();

        String overview = overview(model);
        if (!overview.isEmpty()) {
            builder.append("[설계 문서의 프로젝트 요약]\n").append(overview).append("\n\n");
        }

        builder.append(features(model, names, devlogs, features)).append("\n\n");
        builder.append("[핵심 사용자 흐름]\n").append(flows(model, names)).append("\n\n");
        builder.append("[API 구성]\n").append(apis(model, names)).append("\n\n");
        builder.append("[데이터 구조]\n").append(data(model, names)).append("\n\n");
        builder.append("[설계 점검 결과]\n").append(doctor(model)).append("\n\n");
        builder.append("[구현 근거 요약]\n").append(evidenceSummary(features));

        return new DesignBrief(builder.toString(), List.copyOf(features));
    }

    private String overview(DesignModelV2 model) {
        List<String> lines = new ArrayList<>();

        if (!model.meta().projectSummary().isBlank()) {
            lines.add(oneLine(model.meta().projectSummary()));
        }

        TechStackV2 stack = model.meta().techStack();
        List<String> parts = new ArrayList<>();
        if (!stack.frontend().isBlank()) parts.add("화면 " + stack.frontend());
        if (!stack.backend().isBlank()) parts.add("서버 " + stack.backend());
        if (!stack.db().isBlank()) parts.add("데이터베이스 " + stack.db());
        if (!parts.isEmpty()) {
            lines.add("설계상 구성: " + String.join(", ", parts));
        }

        return String.join("\n", lines);
    }

    /**
     * 요구사항을 분류별로 묶고, 기능마다 화면·API 역할·테이블·연결 상태·구현 근거를 모은다.
     * 보고서의 "주요 기능"과 "개발 결과"가 모두 여기서 나온다.
     */
    private String features(DesignModelV2 model, Names names, List<DevlogInfo> devlogs,
                            List<ReportFeature> out) {
        if (model.requirements().isEmpty()) {
            return "[기능별 정리]\n- 작성된 요구사항이 없습니다.";
        }

        Map<String, List<RequirementV2>> byCategory = new LinkedHashMap<>();
        model.requirements().forEach(requirement -> byCategory
                .computeIfAbsent(requirement.category().isBlank() ? "기타" : requirement.category(),
                        key -> new ArrayList<>())
                .add(requirement));

        Map<String, Long> priorities = model.requirements().stream()
                .collect(Collectors.groupingBy(r -> priority(r.priority()), LinkedHashMap::new,
                        Collectors.counting()));

        int linked = 0;
        StringBuilder body = new StringBuilder();

        for (Map.Entry<String, List<RequirementV2>> entry : byCategory.entrySet()) {
            body.append("■ ").append(entry.getKey()).append("\n");

            for (RequirementV2 requirement : entry.getValue()) {
                String label = (requirement.code().isBlank() ? "" : requirement.code() + " ")
                        + requirement.name();
                body.append("- ").append(label).append(" [").append(priority(requirement.priority()))
                        .append("]\n");

                if (!requirement.description().isBlank()) {
                    body.append("  내용: ").append(oneLine(requirement.description())).append("\n");
                }

                List<String> screenNames = names.screens(requirement.screenIds());
                if (!screenNames.isEmpty()) {
                    body.append("  화면: ").append(String.join(", ", screenNames)).append("\n");
                }

                List<ApiSpecV2> apis = requirement.apiIds().stream()
                        .map(names.apiById::get).filter(api -> api != null).toList();
                if (!apis.isEmpty()) {
                    body.append("  API 역할: ").append(apis.stream().map(this::apiRole)
                            .distinct().collect(Collectors.joining(", "))).append("\n");
                }

                Set<String> tables = new LinkedHashSet<>();
                apis.forEach(api -> tables.addAll(names.tables(api.tableIds())));
                if (!tables.isEmpty()) {
                    body.append("  사용 데이터: ").append(String.join(", ", tables)).append("\n");
                }

                String gap = gap(!screenNames.isEmpty(), !apis.isEmpty(), !tables.isEmpty());
                if (gap.isEmpty()) {
                    linked++;
                }
                body.append("  설계 연결: ").append(gap.isEmpty() ? "화면·API·테이블까지 연결됨" : gap)
                        .append("\n");

                DevlogEvidence.Match match = DevlogEvidence.find(requirement.name(), devlogs);
                body.append("  구현 근거: ").append(describe(match)).append("\n");

                out.add(new ReportFeature(label, requirement.name(),
                        "must".equals(requirement.priority()), match.evidence()));
            }
        }

        String head = "[기능별 정리] 요구사항 " + model.requirements().size() + "개 ("
                + priorities.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(" · "))
                + "), 이 중 " + linked + "개가 설계상 화면·API·테이블까지 연결됨\n";

        return head + body.toString().stripTrailing();
    }

    private String gap(boolean hasScreen, boolean hasApi, boolean hasTable) {
        List<String> gaps = new ArrayList<>();
        if (!hasScreen) gaps.add("담당 화면 없음");
        if (!hasApi) gaps.add("담당 API 없음");
        else if (!hasTable) gaps.add("API 는 있으나 저장할 테이블 미정");
        return String.join(", ", gaps);
    }

    static String describe(DevlogEvidence.Match match) {
        String logs = match.devlogs().stream()
                .map(devlog -> (devlog.getDate() == null ? "" : devlog.getDate() + " ")
                        + "「" + (devlog.getTitle() == null ? "" : devlog.getTitle()) + "」")
                .collect(Collectors.joining(", "));

        return switch (match.evidence()) {
            case RECORDED -> "개발일지에 구현 기록 있음 (" + logs + ")";
            case PARTIAL -> "개발일지에 미완성으로 기록됨 (" + logs + ")";
            case NONE -> "개발일지에 기록 없음 — 구현 여부 확인 필요";
        };
    }

    /** API 하나를 경로가 아니라 하는 일로 적는다. 설명이 비어 있을 때만 경로를 쓴다. */
    private String apiRole(ApiSpecV2 api) {
        String role = api.description().isBlank() ? api.method() + " " + api.endpoint()
                : oneLine(api.description());
        return api.auth() ? role + "(로그인 필요)" : role;
    }

    /**
     * 시작 화면에서 출발하는 대표 경로를 고른다.
     *
     * 화면 전환을 하나하나 적으면 AI 도 그걸 그대로 옮긴다. 사용자가 실제로 밟는
     * 길 몇 개로 묶어 주면 "사용자는 ~에서 ~를 거쳐 ~한다"는 문장이 나온다.
     * 새로 덮는 화면이 많은 경로부터 고르므로 적은 수로 대부분의 화면을 덮는다.
     */
    private String flows(DesignModelV2 model, Names names) {
        if (model.screens().isEmpty()) {
            return "- 작성된 화면이 없습니다.";
        }

        Map<String, List<ScreenTransitionV2>> outgoing = new LinkedHashMap<>();
        Set<String> hasIncoming = new HashSet<>();
        for (ScreenTransitionV2 transition : model.screenTransitions()) {
            if (names.screenById.containsKey(transition.from())
                    && names.screenById.containsKey(transition.to())) {
                outgoing.computeIfAbsent(transition.from(), key -> new ArrayList<>()).add(transition);
                hasIncoming.add(transition.to());
            }
        }

        List<String> starts = model.screens().stream().filter(ScreenV2::isEntry).map(ScreenV2::id)
                .collect(Collectors.toCollection(ArrayList::new));
        if (starts.isEmpty()) {
            model.screens().stream().map(ScreenV2::id).filter(id -> !hasIncoming.contains(id))
                    .forEach(starts::add);
        }
        if (starts.isEmpty()) {
            starts.add(model.screens().get(0).id());
        }

        List<List<ScreenTransitionV2>> paths = new ArrayList<>();
        for (String start : starts) {
            walk(start, outgoing, new ArrayList<>(), new LinkedHashSet<>(List.of(start)), paths);
        }

        StringBuilder builder = new StringBuilder();
        builder.append("시작 화면: ").append(String.join(", ", names.screens(starts))).append("\n");

        Set<String> covered = new HashSet<>(starts);
        List<List<ScreenTransitionV2>> chosen = new ArrayList<>();

        while (chosen.size() < MAX_FLOWS) {
            List<ScreenTransitionV2> best = null;
            int bestGain = 0;

            for (List<ScreenTransitionV2> path : paths) {
                int gain = (int) path.stream().map(ScreenTransitionV2::to)
                        .filter(id -> !covered.contains(id)).distinct().count();
                if (gain > bestGain) {
                    best = path;
                    bestGain = gain;
                }
            }

            if (best == null) {
                break;
            }

            chosen.add(best);
            best.forEach(transition -> covered.add(transition.to()));
        }

        for (List<ScreenTransitionV2> path : chosen) {
            builder.append("- ").append(names.screenById.get(path.get(0).from()).name());
            for (ScreenTransitionV2 transition : path) {
                builder.append(" →");
                if (!transition.trigger().isBlank()) {
                    builder.append("(").append(transition.trigger()).append(")");
                }
                builder.append(" ").append(names.screenById.get(transition.to()).name());
            }
            builder.append("\n");
        }

        List<String> authScreens = model.screens().stream().filter(ScreenV2::requiresAuth)
                .map(ScreenV2::name).toList();
        if (!authScreens.isEmpty()) {
            builder.append("로그인이 필요한 화면: ").append(String.join(", ", authScreens)).append("\n");
        }

        List<String> isolated = model.screens().stream().map(ScreenV2::id)
                .filter(id -> !covered.contains(id)).toList();
        if (!isolated.isEmpty()) {
            builder.append("흐름에 연결되지 않은 화면: ")
                    .append(String.join(", ", names.screens(isolated))).append("\n");
        }

        return builder.toString().stripTrailing();
    }

    /** 끝까지 간 경로만 모은다. 돌아가는 길(로그인 ↔ 회원가입)은 한 번만 밟는다. */
    private void walk(String at, Map<String, List<ScreenTransitionV2>> outgoing,
                      List<ScreenTransitionV2> path, Set<String> visited,
                      List<List<ScreenTransitionV2>> out) {
        if (out.size() >= MAX_PATH_SEARCH) {
            return;
        }

        boolean extended = false;

        for (ScreenTransitionV2 transition : outgoing.getOrDefault(at, List.of())) {
            if (visited.contains(transition.to())) {
                continue;
            }

            extended = true;
            path.add(transition);
            visited.add(transition.to());
            walk(transition.to(), outgoing, path, visited, out);
            visited.remove(transition.to());
            path.remove(path.size() - 1);
        }

        if (!extended && !path.isEmpty()) {
            out.add(List.copyOf(path));
        }
    }

    /**
     * API 는 기능별 정리에 역할로 이미 들어갔다. 여기서는 전체 규모와 방식,
     * 그리고 어느 기능에도 속하지 않은 API 만 적는다.
     */
    private String apis(DesignModelV2 model, Names names) {
        if (model.apis().isEmpty()) {
            return "- 작성된 API가 없습니다.";
        }

        Map<String, Long> byMethod = model.apis().stream()
                .collect(Collectors.groupingBy(ApiSpecV2::method, LinkedHashMap::new, Collectors.counting()));
        long auth = model.apis().stream().filter(ApiSpecV2::auth).count();

        Set<String> resources = new LinkedHashSet<>();
        model.apis().forEach(api -> resources.add(resource(api.endpoint())));

        StringBuilder builder = new StringBuilder();
        builder.append("총 ").append(model.apis().size()).append("개 (")
                .append(byMethod.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                        .collect(Collectors.joining(", ")))
                .append("), 로그인이 필요한 API ").append(auth).append("개\n");
        builder.append("자원 단위: ").append(String.join(", ", resources)).append("\n");

        Set<String> linkedApiIds = new HashSet<>();
        model.requirements().forEach(requirement -> linkedApiIds.addAll(requirement.apiIds()));
        List<String> unlinked = model.apis().stream()
                .filter(api -> !linkedApiIds.contains(api.id()) && api.requirementIds().isEmpty())
                .map(this::apiRole).toList();

        if (!unlinked.isEmpty()) {
            builder.append("요구사항과 연결되지 않은 API: ").append(String.join(", ", unlinked)).append("\n");
        }

        return builder.toString().stripTrailing();
    }

    private String resource(String endpoint) {
        String[] parts = endpoint.replaceFirst("^/?api/?", "").split("/");
        for (String part : parts) {
            if (!part.isBlank() && !part.startsWith("{") && !part.startsWith(":")) {
                return part;
            }
        }
        return endpoint;
    }

    /**
     * 테이블마다 무엇을 저장하고 어느 기능이 쓰는지, 무엇과 이어지는지만 적는다.
     * 컬럼은 자료형 없이 업무 의미가 있는 것 몇 개만 고른다(id, 외래키, 시각 제외).
     */
    private String data(DesignModelV2 model, Names names) {
        if (model.erd().tables().isEmpty()) {
            return "- 작성된 테이블이 없습니다.";
        }

        Map<String, Set<String>> featuresByTable = new LinkedHashMap<>();
        for (RequirementV2 requirement : model.requirements()) {
            for (String apiId : requirement.apiIds()) {
                ApiSpecV2 api = names.apiById.get(apiId);
                if (api == null) continue;
                for (String tableId : api.tableIds()) {
                    featuresByTable.computeIfAbsent(tableId, key -> new LinkedHashSet<>())
                            .add(requirement.name());
                }
            }
        }

        Map<String, List<String>> referencedBy = new LinkedHashMap<>();
        for (RelationV2 relation : model.erd().relations()) {
            TableV2 from = names.tableById.get(relation.fromTableId());
            TableV2 to = names.tableById.get(relation.toTableId());
            if (from == null || to == null) continue;

            String note = relation.note().isBlank() ? "" : " " + oneLine(relation.note());
            referencedBy.computeIfAbsent(to.id(), key -> new ArrayList<>())
                    .add(from.name() + "(" + relation.cardinality() + note + ")");
        }

        StringBuilder builder = new StringBuilder();
        builder.append("테이블 ").append(model.erd().tables().size()).append("개, 관계 ")
                .append(model.erd().relations().size()).append("개\n");

        // 다른 테이블이 많이 기대는 테이블이 중심이다. 그 순서로 적는다.
        List<TableV2> ordered = new ArrayList<>(model.erd().tables());
        ordered.sort((a, b) -> Integer.compare(
                referencedBy.getOrDefault(b.id(), List.of()).size(),
                referencedBy.getOrDefault(a.id(), List.of()).size()));

        for (TableV2 table : ordered) {
            builder.append("- ").append(table.name());
            if (!table.description().isBlank()) {
                builder.append("(").append(oneLine(table.description())).append(")");
            }

            List<String> keyColumns = table.columns().stream()
                    .filter(column -> !column.isPk() && !column.isFk()
                            && !AUDIT_COLUMN.matcher(column.name().toLowerCase()).matches())
                    // 설명이 달린 컬럼이 업무 의미를 더 잘 드러낸다. 그것부터 고른다.
                    .sorted(Comparator.comparing((ColumnV2 column) -> column.comment().isBlank()))
                    .limit(MAX_KEY_COLUMNS)
                    .map(this::columnLabel)
                    .toList();
            if (!keyColumns.isEmpty()) {
                builder.append(": ").append(String.join(", ", keyColumns));
            }
            builder.append("\n");

            Set<String> usedBy = featuresByTable.get(table.id());
            if (usedBy != null) {
                builder.append("  쓰는 기능: ").append(String.join(", ", usedBy)).append("\n");
            }

            List<String> children = referencedBy.get(table.id());
            if (children != null) {
                builder.append("  이 테이블을 참조: ").append(String.join(", ", children)).append("\n");
            }
        }

        return builder.toString().stripTrailing();
    }

    private String columnLabel(ColumnV2 column) {
        return column.comment().isBlank() ? column.name()
                : column.name() + "(" + oneLine(column.comment()) + ")";
    }

    /**
     * 같은 규칙에서 나온 지적은 하나로 묶는다. 표마다 같은 경고가 열 번 나오면
     * 보고서도 같은 말을 열 번 한다.
     */
    private String doctor(DesignModelV2 model) {
        DoctorReport report = doctorService.inspect(model);

        StringBuilder builder = new StringBuilder();

        // 진행률은 화면(설계 점검 패널)이 보여 주는 것과 같은 값이어야 한다.
        // 그래서 여기서 따로 세지 않고 점검 결과에 실려 온 것을 그대로 쓴다.
        builder.append("설계 진행률 ").append(report.progress().percent()).append("%")
                .append(" (요구사항 ").append(report.progress().requirements().percent()).append("%")
                .append(", 화면 ").append(report.progress().screens().percent()).append("%")
                .append(", API ").append(report.progress().apis().percent()).append("%")
                .append(", 테이블 ").append(report.progress().tables().percent()).append("%)\n");

        builder.append("오류 ").append(report.errorCount()).append("건, 경고 ")
                .append(report.warningCount()).append("건, 참고 ")
                .append(report.infoCount()).append("건\n");

        Map<String, List<Finding>> groups = new LinkedHashMap<>();
        report.findings().stream()
                .filter(finding -> finding.severity() != Severity.INFO)
                .forEach(finding -> groups.computeIfAbsent(
                        finding.severity() + "|" + finding.ruleId(), key -> new ArrayList<>()).add(finding));

        if (groups.isEmpty()) {
            builder.append("- 고쳐야 할 문제가 발견되지 않았습니다.");
            return builder.toString();
        }

        groups.values().stream().limit(MAX_FINDING_GROUPS).forEach(findings -> {
            Finding first = findings.get(0);
            builder.append("- [").append(first.severity() == Severity.ERROR ? "오류" : "경고").append("] ")
                    .append(first.message());

            // 대상이 있어야 "무엇을 고칠지"를 쓸 수 있다. 많으면 몇 개만 보이고 나머지는 수로 적는다.
            List<String> targets = findings.stream().map(Finding::targetLabel)
                    .filter(label -> label != null && !label.isBlank()).distinct().toList();
            if (!targets.isEmpty()) {
                builder.append(" — 대상: ").append(String.join(", ", targets.stream().limit(3).toList()));
                if (targets.size() > 3) {
                    builder.append(" 외 ").append(targets.size() - 3).append("곳");
                }
            } else if (findings.size() > 1) {
                builder.append(" (같은 지적 ").append(findings.size() - 1).append("건 더)");
            }
            builder.append("\n");
        });

        return builder.toString().stripTrailing();
    }

    static String evidenceSummary(List<ReportFeature> features) {
        if (features.isEmpty()) {
            return "- 요구사항이 없어 정리할 수 없습니다.";
        }

        StringBuilder builder = new StringBuilder();
        appendEvidence(builder, "개발일지로 구현이 확인된 기능", features, ReportFeature.Evidence.RECORDED);
        appendEvidence(builder, "개발일지에 미완성으로 기록된 기능", features, ReportFeature.Evidence.PARTIAL);
        appendEvidence(builder, "구현 여부 확인 필요(개발일지 기록 없음)", features, ReportFeature.Evidence.NONE);
        return builder.toString().stripTrailing();
    }

    private static void appendEvidence(StringBuilder builder, String title,
                                       List<ReportFeature> features, ReportFeature.Evidence evidence) {
        List<String> labels = features.stream().filter(f -> f.evidence() == evidence)
                .map(ReportFeature::label).toList();
        builder.append("- ").append(title).append(": ")
                .append(labels.isEmpty() ? "없음" : String.join(", ", labels)).append("\n");
    }

    static String priority(String value) {
        return switch (value == null ? "" : value) {
            case "must" -> "필수";
            case "could" -> "선택";
            default -> "권장";
        };
    }

    static String oneLine(String value) {
        return value.replace("\n", " ").replace("\r", " ").trim();
    }

    /** id → 이름 조회표. 여러 정리 단계가 함께 쓴다. */
    private static final class Names {
        final Map<String, ScreenV2> screenById = new LinkedHashMap<>();
        final Map<String, ApiSpecV2> apiById = new LinkedHashMap<>();
        final Map<String, TableV2> tableById = new LinkedHashMap<>();

        Names(DesignModelV2 model) {
            model.screens().forEach(screen -> screenById.put(screen.id(), screen));
            model.apis().forEach(api -> apiById.put(api.id(), api));
            model.erd().tables().forEach(table -> tableById.put(table.id(), table));
        }

        List<String> screens(List<String> ids) {
            return ids.stream().map(screenById::get).filter(screen -> screen != null)
                    .map(ScreenV2::name).distinct().toList();
        }

        List<String> tables(List<String> ids) {
            return ids.stream().map(tableById::get).filter(table -> table != null)
                    .map(TableV2::name).distinct().toList();
        }
    }
}
