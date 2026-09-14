package com.myide.backend.service.design.codegen.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.design.codegen.GeneratedFile;
import com.myide.backend.dto.design.v2.ApiSpecV2;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.TableV2;
import com.myide.backend.service.design.codegen.CodegenOptions;
import com.myide.backend.service.design.codegen.CodegenTarget;
import com.myide.backend.service.design.codegen.CrudShape;
import com.myide.backend.service.design.codegen.DesignCodeGenerator;
import com.myide.backend.service.design.codegen.JsonStubShaper;
import com.myide.backend.service.design.codegen.NameMapper;
import com.myide.backend.service.design.codegen.Traceability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API 명세를 컨트롤러와 DTO 로 옮긴다.
 *
 * 몸통을 만드는 기준은 하나다 - <b>기계가 확실히 아는 것만 만든다.</b>
 *
 * 표준 CRUD(연결된 표가 있고 경로가 .../users 또는 .../users/{id} 모양인 것)는
 * 서비스를 불러 몸통까지 채운다. 이때는 요청·응답 예시로 만든 DTO 대신 엔티티를
 * 그대로 주고받는다. 컨트롤러만 DTO 를 쓰면 서비스가 돌려준 엔티티를 옮기는 코드가
 * 필요해지고, 그것은 기계가 지어낼 수 없어 결국 또 TODO 가 되기 때문이다.
 *
 * 그 밖의 API 는 몸통을 만들지 않는다. 무엇을 어떻게 처리할지는 사람이 정할 일이고,
 * 지어낸 구현이 들어 있으면 검토하고 지우는 데 직접 쓰는 것보다 더 걸린다. 대신
 * <b>요구사항과 화면 정보를 주석으로 넣어</b> 이 API 가 왜 있는지 코드에 남긴다.
 */
@Component
@RequiredArgsConstructor
public class SpringControllerDtoGenerator implements DesignCodeGenerator {

    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}/]+)}");

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(CodegenTarget target) {
        return target == CodegenTarget.SPRING_CONTROLLER_DTO;
    }

    @Override
    public List<GeneratedFile> generate(DesignModelV2 model, CodegenOptions options) {
        Map<String, List<ApiSpecV2>> byResource = new LinkedHashMap<>();

        for (ApiSpecV2 api : model.apis()) {
            if (api.endpoint().isBlank()) {
                continue;
            }
            byResource.computeIfAbsent(NameMapper.resourceOf(api.endpoint()),
                    key -> new ArrayList<>()).add(api);
        }

        List<GeneratedFile> files = new ArrayList<>();
        Map<String, String> dtoSources = new LinkedHashMap<>();
        Set<String> usedRecordNames = new LinkedHashSet<>();

        /*
         * DTO 는 renderController 안에서 dtoSources 에 쌓인다. 어느 리소스에서 나온
         * DTO 인지 알아야 미리보기에서 기능별로 묶을 수 있는데, renderController 를
         * 고치지 않고도 "이번 호출로 새로 생긴 이름" 을 보면 알 수 있다.
         */
        Map<String, List<String>> dtoRequirementIds = new LinkedHashMap<>();

        /* renderController 안쪽에서 스텁을 하나라도 냈는지 알리는 통로. */
        AtomicBoolean stubbed = new AtomicBoolean(false);

        for (Map.Entry<String, List<ApiSpecV2>> entry : byResource.entrySet()) {
            String controllerName = NameMapper.toPascalCase(
                    NameMapper.singularize(entry.getKey())) + "Controller";

            Set<String> before = new LinkedHashSet<>(dtoSources.keySet());
            stubbed.set(false);

            String content = renderController(model, entry.getValue(), controllerName, options,
                    dtoSources, usedRecordNames, stubbed);

            List<String> requirementIds = Traceability.requirementIdsOfApis(entry.getValue());

            dtoSources.keySet().stream()
                    .filter(name -> !before.contains(name))
                    .forEach(name -> dtoRequirementIds.put(name, requirementIds));

            files.add(new GeneratedFile(
                    options.javaSourceDir() + "/controller/" + controllerName + ".java",
                    content,
                    CodegenTarget.SPRING_CONTROLLER_DTO,
                    entry.getKey() + " API " + entry.getValue().size() + "개",
                    requirementIds,
                    Traceability.requirementLabels(model, requirementIds),
                    stubbed.get()));
        }

        dtoSources.forEach((name, source) -> {
            List<String> requirementIds = dtoRequirementIds.getOrDefault(name, List.of());

            files.add(new GeneratedFile(
                    options.javaSourceDir() + "/dto/" + name + ".java",
                    source,
                    CodegenTarget.SPRING_CONTROLLER_DTO,
                    "API 요청/응답 " + name,
                    requirementIds,
                    Traceability.requirementLabels(model, requirementIds)));
        });

        return files;
    }

    private String renderController(DesignModelV2 model, List<ApiSpecV2> apis, String controllerName,
                                    CodegenOptions options, Map<String, String> dtoSources,
                                    Set<String> usedRecordNames, AtomicBoolean stubbed) {
        Map<String, TableV2> tableById = new LinkedHashMap<>();
        model.erd().tables().forEach(table -> tableById.put(table.id(), table));

        StringBuilder methods = new StringBuilder();
        Set<String> usedMethodNames = new LinkedHashSet<>();
        Set<String> dtoImports = new LinkedHashSet<>();
        Set<String> extraImports = new LinkedHashSet<>();

        /* 이 컨트롤러가 주입받아야 하는 서비스. 필드이름 -> 타입이름 */
        Map<String, String> serviceFields = new LinkedHashMap<>();

        boolean needsMap = false;
        boolean needsList = false;

        for (ApiSpecV2 api : apis) {
            String methodName = unique(NameMapper.toFunctionName(api.method(), api.endpoint()),
                    usedMethodNames);

            /*
             * 표준 CRUD 면 몸통을 실제로 만든다.
             *
             * 이때는 요청·응답 예시로 만든 DTO 대신 엔티티를 그대로 주고받는다. 서비스가
             * 엔티티를 돌려주는데 컨트롤러만 DTO 를 반환하면 그 사이를 옮기는 코드가
             * 필요해지고, 그 코드는 기계가 지어낼 수 없어 결국 또 TODO 가 된다.
             * 현업 생성기들(JHipster 등)도 표준 CRUD 는 엔티티로 끝낸다.
             */
            CrudShape shape = CrudShape.of(api);
            TableV2 table = shape.isStandard() ? tableById.get(shape.tableId()) : null;

            if (table != null && !table.name().isBlank()) {
                String entityName = NameMapper.toEntityName(table.name(), table.entityName());

                extraImports.add(options.basePackage() + ".domain." + entityName);
                extraImports.add(options.basePackage() + ".service." + entityName + "Service");
                serviceFields.put(
                        SpringServiceGenerator.serviceFieldName(entityName),
                        entityName + "Service");

                needsList = needsList || shape.kind() == CrudShape.Kind.FIND_ALL;

                methods.append(renderCrudMethod(model, api, methodName, entityName,
                        shape.kind(), table, extraImports));
                continue;
            }

            String stem = Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);

            JsonStubShaper.Shape request = hasBody(api.method())
                    ? shapeOf(api.request(), unique(stem + "Request", usedRecordNames), options,
                    api.description(), dtoSources)
                    : JsonStubShaper.mapShape();

            JsonStubShaper.Shape response = shapeOf(api.response(),
                    unique(stem + "Response", usedRecordNames), options,
                    api.description(), dtoSources);

            if (request.hasRecord()) {
                dtoImports.add(options.basePackage() + ".dto." + request.recordName());
            }
            if (response.hasRecord()) {
                dtoImports.add(options.basePackage() + ".dto." + response.recordName());
            }

            needsMap = needsMap || request.javaType().startsWith("Map")
                    || response.javaType().startsWith("Map");
            needsList = needsList || request.javaType().startsWith("List")
                    || response.javaType().startsWith("List");

            stubbed.set(true);
            methods.append(renderMethod(model, api, methodName, request, response));
        }

        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(options.basePackage()).append(".controller;\n\n");

        for (String value : dtoImports) {
            builder.append("import ").append(value).append(";\n");
        }

        for (String value : extraImports) {
            builder.append("import ").append(value).append(";\n");
        }

        builder.append("import org.springframework.http.ResponseEntity;\n");
        builder.append("import org.springframework.web.bind.annotation.*;\n");

        if (needsList) {
            builder.append("import java.util.List;\n");
        }
        if (needsMap) {
            builder.append("import java.util.Map;\n");
        }

        builder.append("\n/**\n");
        builder.append(" * 설계 관리에서 생성된 컨트롤러입니다.\n");
        builder.append(" *\n");
        builder.append(" * 표준 CRUD 인 API 는 몸통까지 만들어 서비스를 부릅니다.\n");
        builder.append(" * 그 밖의 API 는 무엇을 어떻게 처리할지 기계가 알 수 없어 스텁으로 두었으니\n");
        builder.append(" * 직접 채워 주세요. 각 메서드 주석에 그 API 가 어느 요구사항에서 나왔고\n");
        builder.append(" * 어느 화면이 부르는지 적혀 있습니다.\n");
        builder.append(" */\n");
        builder.append("@RestController\n");
        builder.append("public class ").append(controllerName).append(" {\n");

        if (!serviceFields.isEmpty()) {
            builder.append("\n");

            serviceFields.forEach((field, type) ->
                    builder.append("    private final ").append(type).append(" ")
                            .append(field).append(";\n"));

            List<String> params = new ArrayList<>();
            serviceFields.forEach((field, type) -> params.add(type + " " + field));

            builder.append("\n    public ").append(controllerName).append("(")
                    .append(String.join(", ", params)).append(") {\n");

            serviceFields.keySet().forEach(field ->
                    builder.append("        this.").append(field).append(" = ")
                            .append(field).append(";\n"));

            builder.append("    }\n");
        }

        builder.append(methods);
        builder.append("}\n");

        return builder.toString();
    }

    /**
     * 메서드 위에 붙는 주석. 표준 CRUD 든 스텁이든 같은 내용을 붙인다.
     * 이 API 가 왜 있는지 코드에 남기는 것이 설계와 코드를 잇는 최소한의 끈이다.
     */
    private String renderJavadoc(DesignModelV2 model, ApiSpecV2 api) {
        StringBuilder builder = new StringBuilder();

        builder.append("    /**\n");
        builder.append("     * ").append(api.description().isBlank()
                ? api.method() + " " + api.endpoint()
                : api.description().replace("*/", "*")).append("\n");

        List<String> requirements = Traceability.requirementLabels(model, api.requirementIds());
        if (!requirements.isEmpty()) {
            builder.append("     *\n");
            builder.append("     * 요구사항: ").append(String.join(", ", requirements)).append("\n");
        }

        List<String> screens = Traceability.screenLabels(model, api);
        if (!screens.isEmpty()) {
            builder.append("     * 부르는 화면: ").append(String.join(", ", screens)).append("\n");
        }

        if (api.auth()) {
            builder.append("     * 로그인이 필요한 API 입니다.\n");
        }

        builder.append("     */\n");

        return builder.toString();
    }

    /**
     * 표준 CRUD 메서드. 몸통까지 만든다.
     *
     * 여기서 쓰는 서비스 메서드 이름과 id 타입은 SpringServiceGenerator 가 정한 것을
     * 그대로 가져다 쓴다. 두 곳에서 각자 지으면 이름이 어긋나 컴파일되지 않는다.
     */
    private String renderCrudMethod(DesignModelV2 model, ApiSpecV2 api, String methodName,
                                    String entityName, CrudShape.Kind kind, TableV2 table,
                                    Set<String> extraImports) {
        String service = SpringServiceGenerator.serviceFieldName(entityName);
        String idType = simpleType(SpringServiceGenerator.idTypeOf(table), extraImports);
        String entityParam = NameMapper.toCamelCase(entityName);

        String pathParam = null;
        Matcher matcher = PATH_PARAM.matcher(api.endpoint());
        if (matcher.find()) {
            pathParam = matcher.group(1);
        }

        StringBuilder builder = new StringBuilder("\n");
        builder.append(renderJavadoc(model, api));
        builder.append("    @").append(annotationOf(api.method()))
                .append("(\"").append(api.endpoint()).append("\")\n");

        String idName = pathParam == null ? "id" : NameMapper.toParamName(pathParam);
        String idArg = pathParam == null
                ? ""
                : "@PathVariable(\"" + pathParam + "\") " + idType + " " + idName;

        switch (kind) {
            case FIND_ALL -> {
                builder.append("    public ResponseEntity<List<").append(entityName).append(">> ")
                        .append(methodName).append("() {\n");
                builder.append("        return ResponseEntity.ok(").append(service)
                        .append(".findAll());\n");
            }
            case FIND_ONE -> {
                builder.append("    public ResponseEntity<").append(entityName).append("> ")
                        .append(methodName).append("(").append(idArg).append(") {\n");
                builder.append("        return ResponseEntity.ok(").append(service)
                        .append(".findById(").append(idName).append("));\n");
            }
            case CREATE -> {
                builder.append("    public ResponseEntity<").append(entityName).append("> ")
                        .append(methodName).append("(@RequestBody ").append(entityName)
                        .append(" ").append(entityParam).append(") {\n");
                builder.append("        return ResponseEntity.ok(").append(service)
                        .append(".create(").append(entityParam).append("));\n");
            }
            case UPDATE -> {
                builder.append("    public ResponseEntity<").append(entityName).append("> ")
                        .append(methodName).append("(").append(idArg)
                        .append(", @RequestBody ").append(entityName).append(" ")
                        .append(entityParam).append(") {\n");
                builder.append("        return ResponseEntity.ok(").append(service)
                        .append(".update(").append(idName).append(", ").append(entityParam)
                        .append("));\n");
            }
            default -> {
                builder.append("    public ResponseEntity<Void> ")
                        .append(methodName).append("(").append(idArg).append(") {\n");
                builder.append("        ").append(service).append(".delete(").append(idName)
                        .append(");\n");
                builder.append("        return ResponseEntity.noContent().build();\n");
            }
        }

        builder.append("    }\n");

        return builder.toString();
    }

    private String simpleType(String javaType, Set<String> imports) {
        int lastDot = javaType.lastIndexOf('.');

        if (lastDot < 0) {
            return javaType;
        }

        imports.add(javaType);
        return javaType.substring(lastDot + 1);
    }

    private String renderMethod(DesignModelV2 model, ApiSpecV2 api, String methodName,
                                JsonStubShaper.Shape request, JsonStubShaper.Shape response) {
        StringBuilder builder = new StringBuilder("\n");

        builder.append(renderJavadoc(model, api));
        builder.append("    @").append(annotationOf(api.method()))
                .append("(\"").append(api.endpoint()).append("\")\n");

        List<String> params = new ArrayList<>();

        Matcher matcher = PATH_PARAM.matcher(api.endpoint());
        while (matcher.find()) {
            String raw = matcher.group(1);
            params.add("@PathVariable(\"" + raw + "\") String " + NameMapper.toParamName(raw));
        }

        if (hasBody(api.method())) {
            params.add("@RequestBody " + request.javaType() + " request");
        }

        builder.append("    public ResponseEntity<").append(response.javaType()).append("> ")
                .append(methodName).append("(").append(String.join(", ", params)).append(") {\n");
        builder.append("        // TODO: 구현해 주세요.\n");
        builder.append("        throw new UnsupportedOperationException(\"아직 구현되지 않았습니다.\");\n");
        builder.append("    }\n");

        return builder.toString();
    }

    private JsonStubShaper.Shape shapeOf(String rawJson, String recordName, CodegenOptions options,
                                         String description, Map<String, String> dtoSources) {
        JsonStubShaper.Shape shape = JsonStubShaper.shape(
                objectMapper, rawJson, recordName, options.basePackage(), description);

        if (shape.hasRecord()) {
            dtoSources.put(shape.recordName(), shape.recordSource());
        }

        return shape;
    }

    private boolean hasBody(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private String annotationOf(String method) {
        return switch (method) {
            case "POST" -> "PostMapping";
            case "PUT" -> "PutMapping";
            case "PATCH" -> "PatchMapping";
            case "DELETE" -> "DeleteMapping";
            default -> "GetMapping";
        };
    }

    private String unique(String candidate, Set<String> used) {
        String name = candidate;
        int suffix = 2;

        while (!used.add(name)) {
            name = candidate + suffix++;
        }

        return name;
    }
}
