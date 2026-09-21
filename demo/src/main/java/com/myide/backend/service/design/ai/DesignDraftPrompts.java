package com.myide.backend.service.design.ai;

import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.RequirementV2;
import com.myide.backend.dto.design.v2.ScreenV2;
import com.myide.backend.dto.design.v2.TableV2;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI에게 줄 지시문과 응답 스키마.
 *
 * 두 번에 나눠 부르는 이유가 있다. 한 번에 네 종류를 다 뽑으면 길이 상한에
 * 걸리는 것도 문제지만, 더 큰 문제는 뒤에 나오는 API와 테이블이 앞서 만든
 * 요구사항·화면을 제대로 가리키지 않는다는 점이다. 나눠서 두 번째 호출에
 * 첫 결과를 그대로 보여 주면 참조가 정확해진다.
 *
 * 두 번째 호출에서는 이미 확정된 진짜 id를 그대로 넘긴다. AI는 그것을 베끼기만
 * 하면 되므로 임시 키를 실제 id로 옮기는 단계가 필요 없다.
 *
 * <b>개수는 일부러 적게 잡았다.</b> 예전에는 1단계에만 상한이 있고 2단계 표와
 * API 에는 제한이 한 줄도 없어서, 표마다 CRUD 가 붙으며 초안이 감당 못 할
 * 분량으로 나왔다. 학생 팀은 그걸 다듬을 시간에 설계를 포기한다. 모자라면
 * 사람이 한 줄 더 적는 것은 쉽지만, 넘치는 것을 지우는 일은 하지 않는다.
 * 그래서 "있으면 좋은 것" 을 빼고 없으면 성립하지 않는 것만 내게 한다.
 * 숫자를 다시 올릴 때는 이 이유를 먼저 확인할 것.
 */
public final class DesignDraftPrompts {

    private DesignDraftPrompts() {
    }

    /** MySQL 기준. 설계 점검과 코드 생성이 아는 타입만 쓰게 한다. */
    private static final String ALLOWED_TYPES =
            "BIGINT, INT, BOOLEAN, DECIMAL, VARCHAR, TEXT, LONGTEXT, DATE, DATETIME, TIMESTAMP, JSON";

    // ── 1단계: 요구사항 + 화면 + 화면 이동 ──────────────────────────

    public static String skeletonPrompt(String summary, String backend, String frontend,
                                        String db, String instruction, DesignModelV2 existing) {
        StringBuilder builder = new StringBuilder();

        builder.append("당신은 학생 팀의 프로젝트 설계를 돕는 선배 개발자입니다.\n");
        builder.append("아래 한 줄 설명을 읽고, 이 서비스에 필요한 기능 요구사항과 화면 구성을 정리해 주세요.\n\n");

        builder.append("서비스 설명: ").append(summary).append("\n");
        builder.append("기술 스택: 백엔드 ").append(orUnknown(backend))
                .append(" / 프론트엔드 ").append(orUnknown(frontend))
                .append(" / 데이터베이스 ").append(orUnknown(db)).append("\n");

        if (instruction != null && !instruction.isBlank()) {
            builder.append("추가 요청: ").append(instruction).append("\n");
        }

        appendExisting(builder, existing);

        builder.append("""

                가장 중요한 원칙:
                이 서비스가 "없으면 성립하지 않는 것"만 내세요. 있으면 좋은 기능,
                나중에 붙여도 되는 기능, 관리자 화면, 통계·알림·검색 같은 곁가지는
                빼세요. 학생 팀이 실제로 만들어 낼 수 있는 최소 구성이 목표입니다.
                적게 내는 것이 많이 내는 것보다 낫습니다.

                지켜야 할 것:
                - 요구사항은 5개에서 8개 사이로, 실제로 만들 수 있는 크기로 나눠 주세요. (이미 있는 것을 알려 준 경우에는 그보다 적어도 됩니다.)
                - 화면은 3개에서 5개 사이로 만들고, 사용자가 처음 보는 화면 하나만 isEntry를 true로 하세요.
                - 모든 화면은 requirementKeys에 최소 하나의 요구사항 키를 넣어야 합니다. 왜 필요한 화면인지 근거가 있어야 합니다.
                - 모든 요구사항은 screenKeys에 최소 하나의 화면 키를 넣어야 합니다.
                - 화면 이동(transitions)의 trigger에는 "로그인 버튼 클릭"처럼 사용자가 실제로 하는 행동을 적으세요.
                - route는 /login 처럼 슬래시로 시작하는 영문 소문자 경로로 적으세요. 한글이나 공백을 쓰면 안 됩니다.
                - key 값은 req-login, scr-login 처럼 영문 소문자와 하이픈으로만 만들고 서로 겹치지 않게 하세요.
                - 모든 설명은 한국어로 적으세요.
                """);

        return builder.toString();
    }

    public static Map<String, Object> skeletonSchema() {
        Map<String, Object> requirement = object(Map.of(
                "key", string(),
                "category", string(),
                "name", string(),
                "description", string(),
                "priority", enumOf("must", "should", "could"),
                "screenKeys", arrayOf(string())
        ), List.of("key", "name", "description", "priority", "screenKeys"));

        Map<String, Object> screen = object(Map.of(
                "key", string(),
                "route", string(),
                "name", string(),
                "description", string(),
                "role", enumOf("page", "modal", "external"),
                "isEntry", bool(),
                "requiresAuth", bool(),
                "requirementKeys", arrayOf(string())
        ), List.of("key", "route", "name", "role", "isEntry", "requirementKeys"));

        Map<String, Object> transition = object(Map.of(
                "fromKey", string(),
                "toKey", string(),
                "trigger", string(),
                "kind", enumOf("navigate", "submit", "redirect", "back"),
                "condition", string()
        ), List.of("fromKey", "toKey", "trigger"));

        return object(Map.of(
                "requirements", arrayOf(requirement),
                "screens", arrayOf(screen),
                "transitions", arrayOf(transition)
        ), List.of("requirements", "screens", "transitions"));
    }

    // ── 2단계: 테이블 + 관계 + API ──────────────────────────────────

    public static String detailPrompt(DesignModelV2 skeleton, String backend, String db,
                                      String instruction, DesignModelV2 existing) {
        StringBuilder builder = new StringBuilder();

        builder.append("아래는 방금 정리한 요구사항과 화면입니다. 이것을 그대로 두고, 이제 데이터베이스 표와 API를 설계해 주세요.\n\n");

        builder.append("[요구사항]\n");
        for (RequirementV2 requirement : skeleton.requirements()) {
            builder.append("- ").append(requirement.id()).append(" : ")
                    .append(requirement.name()).append(" — ")
                    .append(requirement.description()).append("\n");
        }

        builder.append("\n[화면]\n");
        for (ScreenV2 screen : skeleton.screens()) {
            builder.append("- ").append(screen.id()).append(" : ")
                    .append(screen.name()).append(" (").append(screen.key()).append(")\n");
        }

        builder.append("\n기술 스택: 백엔드 ").append(orUnknown(backend))
                .append(" / 데이터베이스 ").append(orUnknown(db)).append("\n");

        if (instruction != null && !instruction.isBlank()) {
            builder.append("추가 요청: ").append(instruction).append("\n");
        }

        appendExisting(builder, existing);

        builder.append("""

                가장 중요한 원칙:
                위 요구사항을 돌아가게 하는 데 꼭 필요한 표와 API만 내세요.
                표마다 조회·생성·수정·삭제를 기계적으로 채우지 마세요. 그 화면에서
                실제로 부르는 것만 있으면 됩니다. 적게 내는 것이 많이 내는 것보다 낫습니다.

                지켜야 할 것:
                - 표는 3개에서 6개, API는 6개에서 10개 사이로 만드세요.
                - 위에 적힌 id를 그대로 베껴 쓰세요. 새로 만들거나 바꾸면 안 됩니다.
                - 모든 API는 requirementIds와 screenIds에 위 목록의 id를 최소 하나씩 넣어야 합니다.
                - 모든 API는 tableNames에 자신이 읽거나 쓰는 표 이름을 넣어야 합니다.
                - 모든 표는 최소 하나의 API에서 쓰여야 합니다. 아무도 안 쓰는 표는 만들지 마세요.
                - 표 이름과 컬럼 이름은 영문 소문자와 밑줄로 짓고, 복수형 표 이름을 쓰세요(users, products).
                - 모든 표에는 id 컬럼을 두고 isPk를 true로 하세요.
                - order, group, key, class 처럼 SQL이나 자바에서 이미 쓰는 단어는 컬럼 이름으로 쓰지 마세요.
                - 컬럼 타입은 다음에서만 고르세요: %s
                - 외래키 컬럼의 타입은 가리키는 기본키와 같아야 합니다.
                - API 경로는 /api 로 시작하고, 동사 없이 대상만 적으세요(/api/products).
                - request와 response에는 실제 JSON 예시를 문자열로 적으세요.
                - 모든 설명은 한국어로 적으세요.
                """.formatted(ALLOWED_TYPES));

        return builder.toString();
    }

    /**
     * 이미 있는 것을 알려 준다.
     *
     * 이 말이 없으면 AI 는 빈 문서에 처음부터 만드는 줄 알고, 이미 있는
     * 요구사항과 화면을 다시 내놓는다. 그러면 같은 경로를 쓰는 화면이 생겨
     * 설계 점검 오류가 붙고 코드 생성이 막힌다.
     */
    private static void appendExisting(StringBuilder builder, DesignModelV2 existing) {
        if (existing == null) {
            return;
        }

        List<String> requirements = existing.requirements().stream()
                .map(RequirementV2::name)
                .filter(name -> !name.isBlank())
                .toList();

        List<String> screens = existing.screens().stream()
                .map(screen -> screen.key().isBlank()
                        ? screen.name()
                        : screen.name() + " (" + screen.key() + ")")
                .filter(name -> !name.isBlank())
                .toList();

        List<String> tables = existing.erd().tables().stream()
                .map(TableV2::name)
                .filter(name -> !name.isBlank())
                .toList();

        if (requirements.isEmpty() && screens.isEmpty() && tables.isEmpty()) {
            return;
        }

        builder.append("\n이미 문서에 있는 것 (다시 만들지 마세요):\n");

        if (!requirements.isEmpty()) {
            builder.append("- 요구사항: ").append(String.join(", ", requirements)).append("\n");
        }
        if (!screens.isEmpty()) {
            builder.append("- 화면: ").append(String.join(", ", screens)).append("\n");
        }
        if (!tables.isEmpty()) {
            builder.append("- 표: ").append(String.join(", ", tables)).append("\n");
        }

        builder.append("위와 겹치지 않는 새 항목만 내세요. 경로도 겹치면 안 됩니다.\n");
    }

    /**
     * 깨진 응답을 고쳐 달라고 부탁하는 말.
     *
     * 처음부터 다시 만들게 하지 않는다. 이미 나온 내용을 살리는 편이 빠르고,
     * 사용자가 적어 넣은 설명과 어긋날 일도 없다.
     */
    public static String repairPrompt(String raw, String parseError) {
        return """
                아래 JSON 이 형식에 맞지 않아 읽을 수 없다.

                [읽으려다 난 오류]
                %s

                [원본]
                %s

                [해야 할 일]
                내용은 최대한 그대로 두고 형식만 고쳐서, 스키마에 맞는 JSON 하나만 출력해라.
                설명이나 코드 울타리를 붙이지 마라. 잘려 있으면 뒤를 자연스럽게 마무리해라.
                """.formatted(
                parseError == null ? "형식 오류" : parseError,
                raw == null ? "" : raw);
    }

    public static Map<String, Object> detailSchema() {
        Map<String, Object> column = object(Map.of(
                "name", string(),
                "type", string(),
                "length", integer(),
                "nullable", bool(),
                "isPk", bool(),
                "comment", string()
        ), List.of("name", "type", "nullable", "isPk"));

        Map<String, Object> table = object(Map.of(
                "name", string(),
                "description", string(),
                "columns", arrayOf(column)
        ), List.of("name", "columns"));

        Map<String, Object> relation = object(Map.of(
                "fromTable", string(),
                "fromColumn", string(),
                "toTable", string(),
                "toColumn", string(),
                "cardinality", enumOf("1:1", "1:N", "N:M")
        ), List.of("fromTable", "fromColumn", "toTable", "toColumn", "cardinality"));

        Map<String, Object> api = object(Map.of(
                "method", enumOf("GET", "POST", "PUT", "PATCH", "DELETE"),
                "endpoint", string(),
                "description", string(),
                "request", string(),
                "response", string(),
                "auth", bool(),
                "requirementIds", arrayOf(string()),
                "screenIds", arrayOf(string()),
                "tableNames", arrayOf(string())
        ), List.of("method", "endpoint", "description", "requirementIds", "screenIds", "tableNames"));

        return object(Map.of(
                "tables", arrayOf(table),
                "relations", arrayOf(relation),
                "apis", arrayOf(api)
        ), List.of("tables", "relations", "apis"));
    }

    // ── 스키마 만들기 도우미 ────────────────────────────────────────

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "OBJECT");
        node.put("properties", new LinkedHashMap<>(properties));
        node.put("required", required);
        return node;
    }

    private static Map<String, Object> arrayOf(Map<String, Object> items) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "ARRAY");
        node.put("items", items);
        return node;
    }

    private static Map<String, Object> string() {
        return Map.of("type", "STRING");
    }

    private static Map<String, Object> integer() {
        return Map.of("type", "INTEGER");
    }

    private static Map<String, Object> bool() {
        return Map.of("type", "BOOLEAN");
    }

    private static Map<String, Object> enumOf(String... values) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "STRING");
        node.put("enum", List.of(values));
        return node;
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "미정" : value;
    }
}
