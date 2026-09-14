package com.myide.backend.service.aireport;

import com.myide.backend.domain.design.DesignDocSnapshot;
import com.myide.backend.dto.design.v2.ApiSpecV2;
import com.myide.backend.dto.design.v2.ColumnV2;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.DoctorReport;
import com.myide.backend.dto.design.v2.RelationV2;
import com.myide.backend.dto.design.v2.RequirementV2;
import com.myide.backend.dto.design.v2.ScreenTransitionV2;
import com.myide.backend.dto.design.v2.ScreenV2;
import com.myide.backend.dto.design.v2.TableV2;
import com.myide.backend.repository.design.DesignDocSnapshotRepository;
import com.myide.backend.service.design.DesignModelCodec;
import com.myide.backend.service.design.doctor.DesignDoctorService;
import com.myide.backend.service.design.doctor.Finding;
import com.myide.backend.service.design.doctor.Severity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 최종 보고서에 넣을 설계 부분을 서버가 직접 만든다.
 *
 * 예전에는 화면이 설계 데이터를 통째로 담아 보냈다. 그러면 두 가지가 아쉽다.
 * 보내는 쪽이 아는 만큼만 담기므로 <b>화면 흐름처럼 새로 생긴 것이 빠지고</b>,
 * 무엇보다 설계 점검 결과와 요구사항-화면-API-표 사슬을 넣을 수 없다.
 * 그 둘이 보고서에서 가장 쓸모 있는 부분인데도 그렇다.
 *
 * 저장된 설계가 없으면 null 을 돌려주고, 그때는 예전처럼 요청에 담겨 온
 * 값을 쓴다. 아직 새 설계 화면을 한 번도 열지 않은 워크스페이스가 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DesignReportSectionBuilder {

    private final DesignDocSnapshotRepository snapshotRepository;
    private final DesignModelCodec codec;
    private final DesignDoctorService doctorService;

    public String build(String workspaceId) {
        Optional<DesignDocSnapshot> snapshot = snapshotRepository.findByWorkspace_Uuid(workspaceId);

        if (snapshot.isEmpty()) {
            return null;
        }

        DesignModelV2 model;

        try {
            model = codec.fromJson(snapshot.get().getProjectionJson());
        } catch (Exception e) {
            log.warn("⚠️ [보고서] 저장된 설계를 읽지 못해 요청 값으로 대신합니다: {}", e.getMessage());
            return null;
        }

        if (model.requirements().isEmpty() && model.screens().isEmpty()
                && model.apis().isEmpty() && model.erd().tables().isEmpty()) {
            return null;
        }

        StringBuilder builder = new StringBuilder();

        builder.append("[요구사항 정의]\n").append(requirements(model)).append("\n\n");
        builder.append("[화면 흐름]\n").append(screens(model)).append("\n\n");
        builder.append("[API 명세]\n").append(apis(model)).append("\n\n");
        builder.append("[ERD]\n").append(erd(model)).append("\n\n");
        builder.append("[설계 점검 결과]\n").append(doctor(model)).append("\n\n");
        builder.append("[추적성 — 요구사항이 실제로 구현으로 이어졌는가]\n").append(trace(model));

        return builder.toString();
    }

    private String requirements(DesignModelV2 model) {
        if (model.requirements().isEmpty()) {
            return "- 작성된 요구사항이 없습니다.";
        }

        StringBuilder builder = new StringBuilder();

        for (RequirementV2 requirement : model.requirements()) {
            builder.append("- ")
                    .append(requirement.code().isBlank() ? "" : requirement.code() + " ")
                    .append("[").append(requirement.category()).append("] ")
                    .append(requirement.name())
                    .append(" (").append(priority(requirement.priority())).append(")");

            if (!requirement.description().isBlank()) {
                builder.append("\n  설명: ").append(oneLine(requirement.description()));
            }

            builder.append("\n  연결: 화면 ").append(requirement.screenIds().size())
                    .append("개, API ").append(requirement.apiIds().size()).append("개\n");
        }

        return builder.toString().stripTrailing();
    }

    private String screens(DesignModelV2 model) {
        if (model.screens().isEmpty()) {
            return "- 작성된 화면이 없습니다.";
        }

        Map<String, String> nameById = new LinkedHashMap<>();
        model.screens().forEach(screen -> nameById.put(screen.id(), screen.name()));

        StringBuilder builder = new StringBuilder();

        for (ScreenV2 screen : model.screens()) {
            builder.append("- ").append(screen.name());

            if (!screen.key().isBlank()) {
                builder.append(" (").append(screen.key()).append(")");
            }
            if (screen.isEntry()) {
                builder.append(" [시작 화면]");
            }
            if (screen.requiresAuth()) {
                builder.append(" [로그인 필요]");
            }

            builder.append("\n");

            if (!screen.description().isBlank()) {
                builder.append("  설명: ").append(oneLine(screen.description())).append("\n");
            }

            for (ScreenTransitionV2 transition : model.screenTransitions()) {
                if (!screen.id().equals(transition.from())) {
                    continue;
                }

                String target = nameById.get(transition.to());
                if (target == null) {
                    continue;
                }

                builder.append("  이동: ")
                        .append(transition.trigger().isBlank() ? "(행동 미정)" : transition.trigger())
                        .append(" → ").append(target);

                if (!transition.condition().isBlank()) {
                    builder.append(" (조건: ").append(transition.condition()).append(")");
                }

                builder.append("\n");
            }
        }

        return builder.toString().stripTrailing();
    }

    private String apis(DesignModelV2 model) {
        if (model.apis().isEmpty()) {
            return "- 작성된 API가 없습니다.";
        }

        Map<String, String> requirementLabel = new LinkedHashMap<>();
        model.requirements().forEach(requirement -> requirementLabel.put(requirement.id(),
                (requirement.code().isBlank() ? "" : requirement.code() + " ") + requirement.name()));

        Map<String, String> screenName = new LinkedHashMap<>();
        model.screens().forEach(screen -> screenName.put(screen.id(), screen.name()));

        Map<String, String> tableName = new LinkedHashMap<>();
        model.erd().tables().forEach(table -> tableName.put(table.id(), table.name()));

        StringBuilder builder = new StringBuilder();

        for (ApiSpecV2 api : model.apis()) {
            builder.append("- ").append(api.method()).append(" ").append(api.endpoint());

            if (!api.description().isBlank()) {
                builder.append(" — ").append(oneLine(api.description()));
            }
            if (api.auth()) {
                builder.append(" [인증 필요]");
            }

            builder.append("\n");
            appendLinks(builder, "  근거 요구사항: ", api.requirementIds(), requirementLabel);
            appendLinks(builder, "  부르는 화면: ", api.screenIds(), screenName);
            appendLinks(builder, "  쓰는 표: ", api.tableIds(), tableName);

            if (!api.request().isBlank()) {
                builder.append("  요청: ").append(oneLine(api.request())).append("\n");
            }
            if (!api.response().isBlank()) {
                builder.append("  응답: ").append(oneLine(api.response())).append("\n");
            }
        }

        return builder.toString().stripTrailing();
    }

    private String erd(DesignModelV2 model) {
        if (model.erd().tables().isEmpty()) {
            return "- 작성된 표가 없습니다.";
        }

        Map<String, TableV2> tableById = new LinkedHashMap<>();
        model.erd().tables().forEach(table -> tableById.put(table.id(), table));

        StringBuilder builder = new StringBuilder();

        for (TableV2 table : model.erd().tables()) {
            builder.append("- ").append(table.name());

            if (!table.description().isBlank()) {
                builder.append(" (").append(oneLine(table.description())).append(")");
            }

            builder.append("\n");

            for (ColumnV2 column : table.columns()) {
                builder.append("  · ").append(column.name()).append(" ")
                        .append(column.length() == null
                                ? column.type()
                                : column.type() + "(" + column.length() + ")");

                if (column.isPk()) {
                    builder.append(" [PK]");
                }
                if (column.isFk()) {
                    builder.append(" [FK]");
                }
                if (!column.nullable()) {
                    builder.append(" [필수]");
                }
                if (!column.comment().isBlank()) {
                    builder.append(" — ").append(oneLine(column.comment()));
                }

                builder.append("\n");
            }
        }

        if (!model.erd().relations().isEmpty()) {
            builder.append("표 사이의 관계:\n");

            for (RelationV2 relation : model.erd().relations()) {
                TableV2 from = tableById.get(relation.fromTableId());
                TableV2 to = tableById.get(relation.toTableId());

                if (from == null || to == null) {
                    continue;
                }

                builder.append("  · ").append(from.name()).append(" → ").append(to.name())
                        .append(" (").append(relation.cardinality()).append(")");

                if (!relation.note().isBlank()) {
                    builder.append(" ").append(oneLine(relation.note()));
                }

                builder.append("\n");
            }
        }

        return builder.toString().stripTrailing();
    }

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

        List<Finding> notable = report.findings().stream()
                .filter(finding -> finding.severity() != Severity.INFO)
                .limit(20)
                .toList();

        if (notable.isEmpty()) {
            builder.append("- 고쳐야 할 문제가 발견되지 않았습니다.");
            return builder.toString();
        }

        for (Finding finding : notable) {
            builder.append("- [").append(finding.severity()).append("] ")
                    .append(finding.message()).append("\n");
        }

        return builder.toString().stripTrailing();
    }

    /**
     * 요구사항 하나하나가 화면과 API 와 표까지 이어졌는지 본다.
     *
     * 보고서에서 "무엇을 만들기로 했고 실제로 어디까지 설계했는가"를 말할 수
     * 있게 하는 부분이다. 사슬이 끊긴 자리가 곧 남은 일이다.
     */
    private String trace(DesignModelV2 model) {
        if (model.requirements().isEmpty()) {
            return "- 요구사항이 없어 확인할 수 없습니다.";
        }

        Map<String, ApiSpecV2> apiById = new LinkedHashMap<>();
        model.apis().forEach(api -> apiById.put(api.id(), api));

        StringBuilder builder = new StringBuilder();
        int complete = 0;

        for (RequirementV2 requirement : model.requirements()) {
            boolean hasScreen = !requirement.screenIds().isEmpty();
            boolean hasApi = !requirement.apiIds().isEmpty();
            boolean hasTable = requirement.apiIds().stream()
                    .map(apiById::get)
                    .anyMatch(api -> api != null && !api.tableIds().isEmpty());

            if (hasScreen && hasApi && hasTable) {
                complete++;
                continue;
            }

            builder.append("- ")
                    .append(requirement.code().isBlank() ? "" : requirement.code() + " ")
                    .append(requirement.name()).append(": ");

            if (!hasScreen) {
                builder.append("담당 화면 없음. ");
            }
            if (!hasApi) {
                builder.append("담당 API 없음. ");
            } else if (!hasTable) {
                builder.append("API 는 있으나 저장할 표가 정해지지 않음. ");
            }

            builder.append("\n");
        }

        String head = "요구사항 " + model.requirements().size() + "개 중 "
                + complete + "개가 화면·API·표까지 모두 이어져 있습니다.\n";

        return builder.isEmpty()
                ? head + "- 끊긴 곳이 없습니다."
                : head + builder.toString().stripTrailing();
    }

    private void appendLinks(StringBuilder builder, String label, List<String> ids,
                             Map<String, String> names) {
        List<String> labels = ids.stream()
                .map(names::get)
                .filter(name -> name != null && !name.isBlank())
                .toList();

        if (!labels.isEmpty()) {
            builder.append(label).append(String.join(", ", labels)).append("\n");
        }
    }

    private String priority(String value) {
        return switch (value) {
            case "must" -> "필수";
            case "could" -> "선택";
            default -> "권장";
        };
    }

    private String oneLine(String value) {
        return value.replace("\n", " ").replace("\r", " ").trim();
    }
}
