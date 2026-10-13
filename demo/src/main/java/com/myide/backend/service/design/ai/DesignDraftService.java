package com.myide.backend.service.design.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.design.v2.ApiSpecV2;
import com.myide.backend.dto.design.v2.ColumnV2;
import com.myide.backend.dto.design.v2.DesignMetaV2;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.ErdV2;
import com.myide.backend.dto.design.v2.PointV2;
import com.myide.backend.dto.design.v2.RelationV2;
import com.myide.backend.dto.design.v2.RequirementV2;
import com.myide.backend.dto.design.v2.ScreenTransitionV2;
import com.myide.backend.dto.design.v2.ScreenV2;
import com.myide.backend.dto.design.v2.TableV2;
import com.myide.backend.dto.design.v2.TechStackV2;
import com.myide.backend.service.ai.GeminiHttpClient;
import com.myide.backend.service.design.DesignRepairs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AI 초안 생성.
 *
 * AI가 만든 것이 문서에 그대로 들어가는 경로는 없다. 여기서 id를 붙이고,
 * 참조를 맞추고, 양쪽 연결을 채우고, 타입과 기본키를 손본 뒤에야 결과가
 * 나간다. 그리고 그 결과도 사용자가 검토를 거쳐야 문서에 들어간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DesignDraftService {

    private static final int SKELETON_MAX_TOKENS = 16384;
    private static final int DETAIL_MAX_TOKENS = 32768;

    /** 설계 점검이 아는 타입만 남긴다. 모르는 타입은 코드 생성에서 막힌다. */
    private static final Set<String> KNOWN_TYPES = Set.of(
            "BIGINT", "INT", "SMALLINT", "TINYINT", "BOOLEAN", "DECIMAL", "DOUBLE", "FLOAT",
            "VARCHAR", "CHAR", "TEXT", "LONGTEXT", "DATE", "DATETIME", "TIMESTAMP", "TIME",
            "JSON", "BLOB");

    private final GeminiHttpClient gemini;
    private final ObjectMapper objectMapper;

    public boolean isAvailable() {
        return gemini.isConfigured();
    }

    // ── 1단계 ───────────────────────────────────────────────────────

    public DesignModelV2 generateSkeleton(String summary, TechStackV2 stack, String instruction,
                                          DesignModelV2 existing) {
        String prompt = DesignDraftPrompts.skeletonPrompt(
                summary, stack.backend(), stack.frontend(), stack.db(), instruction, existing);

        JsonNode root = call(prompt, DesignDraftPrompts.skeletonSchema(), SKELETON_MAX_TOKENS);

        Map<String, String> requirementIdByKey = new LinkedHashMap<>();
        Map<String, String> screenIdByKey = new LinkedHashMap<>();

        // 먼저 임시 키에 진짜 id를 하나씩 붙인다.
        // AI에게 id를 만들게 하면 형식이 어긋나거나 겹친다.
        for (JsonNode item : root.path("requirements")) {
            requirementIdByKey.computeIfAbsent(item.path("key").asText(""), key -> newId("req"));
        }
        for (JsonNode item : root.path("screens")) {
            screenIdByKey.computeIfAbsent(item.path("key").asText(""), key -> newId("scr"));
        }

        List<RequirementV2> requirements = new ArrayList<>();
        List<ScreenV2> screens = new ArrayList<>();
        List<ScreenTransitionV2> transitions = new ArrayList<>();

        int order = 1;
        for (JsonNode item : root.path("requirements")) {
            String id = requirementIdByKey.get(item.path("key").asText(""));
            if (id == null) {
                continue;
            }

            requirements.add(new RequirementV2(
                    id,
                    String.format("R-%02d", order++),
                    text(item, "category", "기본"),
                    text(item, "name", ""),
                    text(item, "description", ""),
                    text(item, "priority", "should"),
                    resolve(item.path("screenKeys"), screenIdByKey),
                    List.of()
            ));
        }

        // 이미 문서에 있는 경로를 먼저 채워 둔다. 그래야 새로 만드는 화면이
        // 기존 화면과 같은 경로를 쓰지 않는다.
        Set<String> usedRoutes = existingRoutes(existing);

        for (JsonNode item : root.path("screens")) {
            String screenKey = item.path("key").asText("");
            String id = screenIdByKey.get(screenKey);
            if (id == null) {
                continue;
            }

            screens.add(new ScreenV2(
                    id,
                    safeRoute(text(item, "route", ""), screenKey, usedRoutes),
                    text(item, "name", ""),
                    text(item, "description", ""),
                    text(item, "role", "page"),
                    item.path("isEntry").asBoolean(false),
                    item.path("requiresAuth").asBoolean(false),
                    resolve(item.path("requirementKeys"), requirementIdByKey),
                    List.of(),
                    PointV2.origin()
            ));
        }

        for (JsonNode item : root.path("transitions")) {
            String from = screenIdByKey.get(item.path("fromKey").asText(""));
            String to = screenIdByKey.get(item.path("toKey").asText(""));

            // 어느 한쪽이라도 없는 화면을 가리키면 버린다. 끊어진 화살표를 만들지 않는다.
            if (from == null || to == null) {
                continue;
            }

            transitions.add(new ScreenTransitionV2(
                    newId("trn"), from, to,
                    text(item, "trigger", ""),
                    text(item, "kind", "navigate"),
                    text(item, "condition", ""),
                    List.of()
            ));
        }

        requirements = fillRequirementScreens(requirements, screens);
        screens = ensureEntry(screens);
        screens = layoutScreens(screens, transitions);

        return new DesignModelV2(
                DesignModelV2.CURRENT_SCHEMA_VERSION,
                new DesignMetaV2(summary, stack, null),
                requirements, screens, transitions, List.of(), ErdV2.empty());
    }

    // ── 2단계 ───────────────────────────────────────────────────────

    public DesignModelV2 generateDetail(DesignModelV2 skeleton, String instruction,
                                        DesignModelV2 existing) {
        TechStackV2 stack = skeleton.meta().techStack();

        String prompt = DesignDraftPrompts.detailPrompt(
                skeleton, stack.backend(), stack.db(), instruction, existing);

        JsonNode root = call(prompt, DesignDraftPrompts.detailSchema(), DETAIL_MAX_TOKENS);

        Map<String, String> tableIdByName = new LinkedHashMap<>();
        Map<String, Map<String, String>> columnIdByTableAndName = new HashMap<>();
        List<TableV2> tables = new ArrayList<>();

        // 이미 있는 표 이름을 미리 넣어 둔다. 같은 이름의 표가 둘이면
        // 설계 점검이 TBL_DUP_NAME 오류로 잡아 코드 생성이 막힌다.
        // 값은 쓰이지 않으므로 자리만 채운다.
        if (existing != null) {
            existing.erd().tables().forEach(table ->
                    tableIdByName.put(table.name().toLowerCase(Locale.ROOT), table.id()));
        }

        for (JsonNode item : root.path("tables")) {
            String name = text(item, "name", "").trim();
            if (name.isEmpty() || tableIdByName.containsKey(name.toLowerCase(Locale.ROOT))) {
                continue;
            }

            String tableId = newId("tbl");
            tableIdByName.put(name.toLowerCase(Locale.ROOT), tableId);

            Map<String, String> columnIds = new LinkedHashMap<>();
            List<ColumnV2> columns = new ArrayList<>();

            for (JsonNode columnNode : item.path("columns")) {
                String columnName = safeColumnName(text(columnNode, "name", "").trim());
                if (columnName.isEmpty()
                        || columnIds.containsKey(columnName.toLowerCase(Locale.ROOT))) {
                    continue;
                }

                String columnId = newId("col");
                columnIds.put(columnName.toLowerCase(Locale.ROOT), columnId);

                boolean isPk = columnNode.path("isPk").asBoolean(false);

                columns.add(new ColumnV2(
                        columnId,
                        columnName,
                        normalizeType(text(columnNode, "type", "VARCHAR")),
                        columnNode.path("length").isNumber() ? columnNode.path("length").asInt() : null,
                        !isPk && columnNode.path("nullable").asBoolean(true),
                        isPk,
                        false,
                        "",
                        text(columnNode, "comment", "")
                ));
            }

            // 기본키가 없으면 붙여 준다. 없으면 코드 생성이 아예 막힌다.
            if (columns.stream().noneMatch(ColumnV2::isPk)) {
                String columnId = newId("col");
                columnIds.put("id", columnId);
                columns.add(0, new ColumnV2(columnId, "id", "BIGINT", null,
                        false, true, false, "", ""));
            }

            columnIdByTableAndName.put(tableId, columnIds);
            tables.add(new TableV2(tableId, name, "", text(item, "description", ""),
                    columns, PointV2.origin()));
        }

        List<RelationV2> relations = buildRelations(root, tableIdByName, columnIdByTableAndName);
        tables = markForeignKeys(tables, relations);

        List<ApiSpecV2> apis = buildApis(root, skeleton, tableIdByName);

        return new DesignModelV2(
                DesignModelV2.CURRENT_SCHEMA_VERSION,
                skeleton.meta(),
                linkRequirementApis(skeleton.requirements(), apis),
                linkScreenApis(skeleton.screens(), apis),
                skeleton.screenTransitions(),
                apis,
                new ErdV2(layoutTables(tables, relations), relations));
    }

    // ── 후처리 ──────────────────────────────────────────────────────

    private List<RelationV2> buildRelations(JsonNode root,
                                            Map<String, String> tableIdByName,
                                            Map<String, Map<String, String>> columnIds) {
        List<RelationV2> relations = new ArrayList<>();

        for (JsonNode item : root.path("relations")) {
            String fromTable = tableIdByName.get(text(item, "fromTable", "").toLowerCase(Locale.ROOT));
            String toTable = tableIdByName.get(text(item, "toTable", "").toLowerCase(Locale.ROOT));

            if (fromTable == null || toTable == null) {
                continue;
            }

            String fromColumn = columnIds.getOrDefault(fromTable, Map.of())
                    .get(text(item, "fromColumn", "").toLowerCase(Locale.ROOT));
            String toColumn = columnIds.getOrDefault(toTable, Map.of())
                    .get(text(item, "toColumn", "").toLowerCase(Locale.ROOT));

            // 가리키는 컬럼이 실제로 없으면 관계를 만들지 않는다.
            if (fromColumn == null || toColumn == null) {
                continue;
            }

            relations.add(new RelationV2(newId("rel"), fromTable, fromColumn,
                    toTable, toColumn, text(item, "cardinality", "1:N"), "", ""));
        }

        return relations;
    }

    /**
     * 외래키 표시를 하고, 가리키는 기본키와 타입을 맞춘다.
     *
     * AI 는 상품 번호를 BIGINT 로 만들어 놓고 주문 표의 product_id 는 VARCHAR
     * 로 적는 일이 잦다. 타입이 다르면 데이터베이스가 외래키를 걸어 주지 않고,
     * 설계 점검도 REL_TYPE_MISMATCH 오류로 잡아 코드 생성을 막는다. 어느 쪽이
     * 옳은지는 분명하다 — 가리키는 쪽 기본키가 정답이다.
     */
    private List<TableV2> markForeignKeys(List<TableV2> tables, List<RelationV2> relations) {
        Map<String, ColumnV2> columnById = new HashMap<>();
        tables.forEach(table -> table.columns().forEach(column -> columnById.put(column.id(), column)));

        Map<String, ColumnV2> targetByFkColumnId = new HashMap<>();
        for (RelationV2 relation : relations) {
            ColumnV2 target = columnById.get(relation.toColumnId());
            if (target != null) {
                targetByFkColumnId.put(relation.fromColumnId(), target);
            }
        }

        List<TableV2> result = new ArrayList<>();

        for (TableV2 table : tables) {
            List<ColumnV2> columns = table.columns().stream()
                    .map(column -> {
                        ColumnV2 target = targetByFkColumnId.get(column.id());

                        if (target == null) {
                            return column;
                        }

                        return new ColumnV2(column.id(), column.name(),
                                target.type(), target.length(),
                                column.nullable(), column.isPk(), true,
                                column.defaultValue(), column.comment());
                    })
                    .toList();

            result.add(new TableV2(table.id(), table.name(), table.entityName(),
                    table.description(), columns, table.layout()));
        }

        return result;
    }

    private List<ApiSpecV2> buildApis(JsonNode root, DesignModelV2 skeleton,
                                      Map<String, String> tableIdByName) {
        Set<String> requirementIds = new HashSet<>();
        skeleton.requirements().forEach(item -> requirementIds.add(item.id()));

        Set<String> screenIds = new HashSet<>();
        skeleton.screens().forEach(item -> screenIds.add(item.id()));

        List<ApiSpecV2> apis = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (JsonNode item : root.path("apis")) {
            String method = text(item, "method", "GET").toUpperCase(Locale.ROOT);
            String endpoint = text(item, "endpoint", "").trim();

            if (endpoint.isEmpty() || !seen.add(method + " " + endpoint.toLowerCase(Locale.ROOT))) {
                continue;
            }

            List<String> tableIds = new ArrayList<>();
            for (JsonNode name : item.path("tableNames")) {
                String tableId = tableIdByName.get(name.asText("").toLowerCase(Locale.ROOT));
                if (tableId != null && !tableIds.contains(tableId)) {
                    tableIds.add(tableId);
                }
            }

            apis.add(new ApiSpecV2(
                    newId("api"), method, endpoint,
                    text(item, "description", ""),
                    text(item, "request", ""),
                    text(item, "response", ""),
                    item.path("auth").asBoolean(false),
                    crudOf(method),
                    keep(item.path("requirementIds"), requirementIds),
                    keep(item.path("screenIds"), screenIds),
                    tableIds
            ));
        }

        return apis;
    }

    /** API가 가리킨 요구사항의 반대편도 채운다. 한쪽만 있으면 점검이 헛것을 보고한다. */
    private List<RequirementV2> linkRequirementApis(List<RequirementV2> requirements,
                                                    List<ApiSpecV2> apis) {
        Map<String, List<String>> apiIdsByRequirement = new HashMap<>();

        apis.forEach(api -> api.requirementIds().forEach(requirementId ->
                apiIdsByRequirement.computeIfAbsent(requirementId, key -> new ArrayList<>())
                        .add(api.id())));

        return requirements.stream()
                .map(item -> new RequirementV2(item.id(), item.code(), item.category(),
                        item.name(), item.description(), item.priority(), item.screenIds(),
                        apiIdsByRequirement.getOrDefault(item.id(), List.of())))
                .toList();
    }

    private List<ScreenV2> linkScreenApis(List<ScreenV2> screens, List<ApiSpecV2> apis) {
        Map<String, List<String>> apiIdsByScreen = new HashMap<>();

        apis.forEach(api -> api.screenIds().forEach(screenId ->
                apiIdsByScreen.computeIfAbsent(screenId, key -> new ArrayList<>())
                        .add(api.id())));

        return screens.stream()
                .map(item -> new ScreenV2(item.id(), item.key(), item.name(), item.description(),
                        item.role(), item.isEntry(), item.requiresAuth(), item.requirementIds(),
                        apiIdsByScreen.getOrDefault(item.id(), List.of()), item.layout()))
                .toList();
    }

    /** 요구사항이 가리킨 화면의 반대편도 채운다. */
    private List<RequirementV2> fillRequirementScreens(List<RequirementV2> requirements,
                                                       List<ScreenV2> screens) {
        Map<String, List<String>> screenIdsByRequirement = new HashMap<>();

        screens.forEach(screen -> screen.requirementIds().forEach(requirementId ->
                screenIdsByRequirement.computeIfAbsent(requirementId, key -> new ArrayList<>())
                        .add(screen.id())));

        return requirements.stream()
                .map(item -> {
                    List<String> merged = new ArrayList<>(item.screenIds());
                    screenIdsByRequirement.getOrDefault(item.id(), List.of())
                            .forEach(screenId -> {
                                if (!merged.contains(screenId)) {
                                    merged.add(screenId);
                                }
                            });
                    return new RequirementV2(item.id(), item.code(), item.category(), item.name(),
                            item.description(), item.priority(), merged, item.apiIds());
                })
                .toList();
    }

    /** 시작 화면이 하나도 없으면 첫 화면을 시작으로 삼는다. 없으면 점검이 곧바로 오류를 낸다. */
    private List<ScreenV2> ensureEntry(List<ScreenV2> screens) {
        if (screens.isEmpty() || screens.stream().anyMatch(ScreenV2::isEntry)) {
            return screens;
        }

        List<ScreenV2> result = new ArrayList<>(screens);
        ScreenV2 first = result.get(0);

        result.set(0, new ScreenV2(first.id(), first.key(), first.name(), first.description(),
                first.role(), true, first.requiresAuth(), first.requirementIds(),
                first.apiIds(), first.layout()));

        return result;
    }

    /** 시작 화면에서 뻗어 나가는 순서대로 층층이 놓는다. 겹쳐 있으면 읽을 수 없다. */
    private List<ScreenV2> layoutScreens(List<ScreenV2> screens,
                                         List<ScreenTransitionV2> transitions) {
        Map<String, List<String>> next = new HashMap<>();
        transitions.forEach(transition ->
                next.computeIfAbsent(transition.from(), key -> new ArrayList<>())
                        .add(transition.to()));

        Map<String, Integer> depth = new LinkedHashMap<>();
        List<String> queue = new ArrayList<>();

        screens.stream().filter(ScreenV2::isEntry).forEach(screen -> {
            depth.put(screen.id(), 0);
            queue.add(screen.id());
        });

        for (int i = 0; i < queue.size(); i++) {
            String current = queue.get(i);
            for (String child : next.getOrDefault(current, List.of())) {
                if (!depth.containsKey(child)) {
                    depth.put(child, depth.get(current) + 1);
                    queue.add(child);
                }
            }
        }

        Map<Integer, Integer> countPerDepth = new HashMap<>();
        List<ScreenV2> result = new ArrayList<>();
        int orphanRow = 0;

        for (ScreenV2 screen : screens) {
            int level = depth.getOrDefault(screen.id(), -1);

            double x;
            double y;

            if (level < 0) {
                // 어디서도 닿지 않는 화면은 아래쪽에 따로 모아 둔다.
                x = 40;
                y = 560 + orphanRow++ * 150;
            } else {
                int row = countPerDepth.merge(level, 1, Integer::sum) - 1;
                x = 40 + level * 320;
                y = 40 + row * 170;
            }

            result.add(new ScreenV2(screen.id(), screen.key(), screen.name(), screen.description(),
                    screen.role(), screen.isEntry(), screen.requiresAuth(),
                    screen.requirementIds(), screen.apiIds(), new PointV2(x, y)));
        }

        return result;
    }

    /** 가리키는 쪽을 왼쪽에 두는 식으로 관계를 따라 늘어놓는다. */
    private List<TableV2> layoutTables(List<TableV2> tables, List<RelationV2> relations) {
        Map<String, Integer> level = new HashMap<>();
        tables.forEach(table -> level.put(table.id(), 0));

        // 몇 번 돌리면 대부분 자리를 잡는다. 완벽할 필요는 없고 겹치지만 않으면 된다.
        for (int round = 0; round < 3; round++) {
            for (RelationV2 relation : relations) {
                Integer to = level.get(relation.toTableId());
                Integer from = level.get(relation.fromTableId());

                if (to != null && from != null && from <= to) {
                    level.put(relation.fromTableId(), to + 1);
                }
            }
        }

        Map<Integer, Integer> countPerLevel = new HashMap<>();
        List<TableV2> result = new ArrayList<>();

        for (TableV2 table : tables) {
            int column = level.getOrDefault(table.id(), 0);
            int row = countPerLevel.merge(column, 1, Integer::sum) - 1;

            result.add(new TableV2(table.id(), table.name(), table.entityName(),
                    table.description(), table.columns(),
                    new PointV2(40 + column * 340, 40 + row * 260)));
        }

        return result;
    }

    // ── 공용 ────────────────────────────────────────────────────────

    private JsonNode call(String prompt, Map<String, Object> schema, int maxTokens) {
        String raw;
        long startedAt = System.currentTimeMillis();

        try {
            raw = gemini.generate(prompt,
                    GeminiHttpClient.GenerationOptions.json(maxTokens, schema));
            log.info("⏱️ [AI] 초안 호출 {}ms", System.currentTimeMillis() - startedAt);
        } catch (GeminiHttpClient.ResponseTruncatedException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "AI 응답이 너무 길어 잘렸습니다. 설명을 조금 더 좁혀서 다시 시도해 주세요.");
        } catch (GeminiHttpClient.GeminiConfigException e) {
            // 기다려도 풀리지 않는 실패라 "잠시 후 다시" 안내를 하면 안 된다.
            log.error("⚠️ [AI] {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 설정에 문제가 있어 초안을 만들 수 없습니다. 서버 로그를 확인해 주세요.");
        } catch (GeminiHttpClient.GeminiUnavailableException e) {
            log.warn("⚠️ [AI] 초안 생성 실패: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "지금은 AI를 쓸 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }

        try {
            return objectMapper.readTree(stripFence(raw));
        } catch (Exception e) {
            log.warn("⚠️ [AI] 초안 응답을 해석하지 못했습니다: {}", e.getMessage());
            return repair(raw, e.getMessage(), schema, maxTokens);
        }
    }

    /**
     * 깨진 응답을 한 번만 고쳐 달라고 다시 부른다.
     *
     * 구조화 출력을 켜 두었어도 응답이 잘리거나 앞뒤에 설명이 붙어 오는 일이
     * 있다. 사용자에게 "다시 시도해 주세요"라고 돌려보내면 처음부터 20초를
     * 더 기다려야 하는데, 고쳐 달라고 부르는 쪽이 훨씬 빠르고 잘 듣는다.
     *
     * 두 번 이상은 부르지 않는다. 응답 시간만 늘고 성공률은 거의 오르지 않는다.
     */
    private JsonNode repair(String raw, String parseError, Map<String, Object> schema,
                            int maxTokens) {
        String prompt = DesignDraftPrompts.repairPrompt(raw, parseError);

        try {
            String fixed = gemini.generate(prompt,
                    GeminiHttpClient.GenerationOptions.json(maxTokens, schema));

            JsonNode parsed = objectMapper.readTree(stripFence(fixed));
            log.info("✅ [AI] 깨진 응답을 한 번 고쳐서 살렸습니다.");

            return parsed;
        } catch (Exception e) {
            log.warn("⚠️ [AI] 고쳐 달라고 다시 불렀지만 실패했습니다: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "AI 응답을 해석하지 못했습니다. 다시 시도해 주세요.");
        }
    }

    /** 구조화 출력을 쓰면 거의 없지만, 혹시 울타리가 섞여 오면 걷어낸다. */
    private String stripFence(String raw) {
        String trimmed = raw.trim();

        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start >= 0 && end > start) {
                trimmed = trimmed.substring(start + 1, end).trim();
            }
        }

        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');

        return first >= 0 && last > first ? trimmed.substring(first, last + 1) : trimmed;
    }

    private List<String> resolve(JsonNode keys, Map<String, String> idByKey) {
        List<String> result = new ArrayList<>();

        for (JsonNode key : keys) {
            String id = idByKey.get(key.asText(""));
            if (id != null && !result.contains(id)) {
                result.add(id);
            }
        }

        return result;
    }

    private List<String> keep(JsonNode ids, Set<String> allowed) {
        List<String> result = new ArrayList<>();

        for (JsonNode id : ids) {
            String value = id.asText("");
            if (allowed.contains(value) && !result.contains(value)) {
                result.add(value);
            }
        }

        return result;
    }

    /**
     * 화면 경로를 점검 규칙이 받아들이는 모양으로 맞춘다.
     *
     * 프롬프트로 부탁해 두었지만 부탁은 매번 지켜지지 않고, 점검 규칙은 매번
     * 똑같이 오류로 판정한다. 오류가 하나라도 있으면 코드 생성이 막히므로,
     * 규칙이 검사하는 것은 여기서 보장한다.
     *
     * 실제 규칙은 DesignRepairs 에 있다. 설계 점검의 "고치기" 버튼도 같은
     * 함수를 쓰므로 두 곳이 같은 문제를 다르게 고칠 일이 없다.
     */
    private String safeRoute(String raw, String screenKey, Set<String> used) {
        return DesignRepairs.uniqueRoute(raw, screenKey, used);
    }

    /** order, desc 처럼 SQL·자바에서 이미 쓰는 단어는 컬럼 이름으로 쓸 수 없다. */
    private String safeColumnName(String name) {
        return DesignRepairs.renameReservedColumn(name);
    }

    /** 이미 문서에 있는 화면 경로. 새 화면이 이것과 겹치지 않게 한다. */
    private Set<String> existingRoutes(DesignModelV2 existing) {
        Set<String> routes = new HashSet<>();

        if (existing != null) {
            existing.screens().stream()
                    .map(screen -> screen.key().trim().toLowerCase(Locale.ROOT))
                    .filter(route -> !route.isEmpty())
                    .forEach(routes::add);
        }

        return routes;
    }

    private String normalizeType(String type) {
        String upper = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);

        // 길이 표기가 붙어 오면 떼어 낸다. 길이는 따로 담는다.
        int paren = upper.indexOf('(');
        if (paren > 0) {
            upper = upper.substring(0, paren);
        }

        return KNOWN_TYPES.contains(upper) ? upper : "VARCHAR";
    }

    private String crudOf(String method) {
        return switch (method) {
            case "POST" -> "C";
            case "PUT", "PATCH" -> "U";
            case "DELETE" -> "D";
            default -> "R";
        };
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
