package com.myide.backend.service.design.codegen.spring;

import com.myide.backend.dto.design.codegen.GeneratedFile;
import com.myide.backend.dto.design.v2.ApiSpecV2;
import com.myide.backend.dto.design.v2.ColumnV2;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.TableV2;
import com.myide.backend.service.design.codegen.CodegenOptions;
import com.myide.backend.service.design.codegen.CodegenTarget;
import com.myide.backend.service.design.codegen.CrudShape;
import com.myide.backend.service.design.codegen.DesignCodeGenerator;
import com.myide.backend.service.design.codegen.NameMapper;
import com.myide.backend.service.design.codegen.Traceability;
import com.myide.backend.service.design.codegen.TypeMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 표준 CRUD 인 API 의 몸통을 실제로 만든다.
 *
 * 서비스는 표(table) 하나에 하나씩 만든다. 리소스(경로 묶음)가 아니라 표를 기준으로 삼는
 * 이유는, 컨트롤러와 서비스가 같은 엔티티 이름과 같은 id 타입을 써야 컴파일되기 때문이다.
 * 표를 기준으로 두면 두 생성기가 각자 계산해도 결과가 어긋날 수 없다.
 *
 * 만드는 메서드는 그 표를 쓰는 표준 API 에 실제로 있는 것만이다. 조회만 설계했는데
 * 삭제 메서드가 딸려 나오면 쓰지 않는 코드가 남는다.
 */
@Component
public class SpringServiceGenerator implements DesignCodeGenerator {

    @Override
    public boolean supports(CodegenTarget target) {
        return target == CodegenTarget.SPRING_SERVICE;
    }

    @Override
    public List<GeneratedFile> generate(DesignModelV2 model, CodegenOptions options) {
        Map<String, TableV2> tableById = new LinkedHashMap<>();
        model.erd().tables().forEach(table -> tableById.put(table.id(), table));

        /* 표마다 어떤 CRUD 가 필요한지 모은다. */
        Map<String, Set<CrudShape.Kind>> kindsByTable = new LinkedHashMap<>();

        for (ApiSpecV2 api : model.apis()) {
            CrudShape shape = CrudShape.of(api);

            if (!shape.isStandard() || !tableById.containsKey(shape.tableId())) {
                continue;
            }

            kindsByTable
                    .computeIfAbsent(shape.tableId(), key -> EnumSet.noneOf(CrudShape.Kind.class))
                    .add(shape.kind());
        }

        List<GeneratedFile> files = new ArrayList<>();

        for (Map.Entry<String, Set<CrudShape.Kind>> entry : kindsByTable.entrySet()) {
            TableV2 table = tableById.get(entry.getKey());

            if (table == null || table.name().isBlank()) {
                continue;
            }

            String entityName = NameMapper.toEntityName(table.name(), table.entityName());
            List<String> requirementIds = Traceability.requirementIdsForTable(model, table.id());

            files.add(new GeneratedFile(
                    options.javaSourceDir() + "/service/" + entityName + "Service.java",
                    render(table, entityName, entry.getValue(), options),
                    CodegenTarget.SPRING_SERVICE,
                    "테이블 " + table.name(),
                    requirementIds,
                    Traceability.requirementLabels(model, requirementIds)));
        }

        return files;
    }

    /** 컨트롤러가 부를 이름. 두 생성기가 같은 규칙을 써야 하므로 여기 한곳에 둔다. */
    public static String serviceFieldName(String entityName) {
        return NameMapper.toCamelCase(entityName) + "Service";
    }

    public static String methodNameOf(CrudShape.Kind kind) {
        return switch (kind) {
            case FIND_ALL -> "findAll";
            case FIND_ONE -> "findById";
            case CREATE -> "create";
            case UPDATE -> "update";
            case DELETE -> "delete";
            case NONE -> "";
        };
    }

    /** 표의 기본키 자바 타입. 없으면 Long 으로 본다(엔티티 생성기와 같은 기본값). */
    public static String idTypeOf(TableV2 table) {
        return table.columns().stream()
                .filter(ColumnV2::isPk)
                .findFirst()
                .map(TypeMapper::toJavaType)
                .orElse("Long");
    }

    private String render(TableV2 table, String entityName, Set<CrudShape.Kind> kinds,
                          CodegenOptions options) {
        Set<String> imports = new LinkedHashSet<>();
        String idType = simpleType(idTypeOf(table), imports);

        String field = NameMapper.toCamelCase(entityName);
        String repositoryField = field + "Repository";

        StringBuilder methods = new StringBuilder();

        if (kinds.contains(CrudShape.Kind.FIND_ALL)) {
            methods.append("\n    public List<").append(entityName).append("> findAll() {\n");
            methods.append("        return ").append(repositoryField).append(".findAll();\n");
            methods.append("    }\n");
        }

        if (kinds.contains(CrudShape.Kind.FIND_ONE) || kinds.contains(CrudShape.Kind.UPDATE)) {
            methods.append("\n    public ").append(entityName).append(" findById(")
                    .append(idType).append(" id) {\n");
            methods.append("        return ").append(repositoryField).append(".findById(id)\n");
            methods.append("                .orElseThrow(() -> new IllegalArgumentException(\"")
                    .append(table.name()).append(" 를 찾을 수 없습니다: \" + id));\n");
            methods.append("    }\n");
        }

        if (kinds.contains(CrudShape.Kind.CREATE)) {
            methods.append("\n    public ").append(entityName).append(" create(")
                    .append(entityName).append(" ").append(field).append(") {\n");
            methods.append("        return ").append(repositoryField).append(".save(")
                    .append(field).append(");\n");
            methods.append("    }\n");
        }

        if (kinds.contains(CrudShape.Kind.UPDATE)) {
            methods.append("\n    public ").append(entityName).append(" update(")
                    .append(idType).append(" id, ").append(entityName).append(" ")
                    .append(field).append(") {\n");
            methods.append("        // 있는지 먼저 확인한다. 없는 id 로 save 하면 고치는 대신 새로 만들어진다.\n");
            methods.append("        findById(id);\n");
            methods.append("        return ").append(repositoryField).append(".save(")
                    .append(field).append(");\n");
            methods.append("    }\n");
        }

        if (kinds.contains(CrudShape.Kind.DELETE)) {
            methods.append("\n    public void delete(").append(idType).append(" id) {\n");
            methods.append("        ").append(repositoryField).append(".deleteById(id);\n");
            methods.append("    }\n");
        }

        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(options.basePackage()).append(".service;\n\n");
        builder.append("import ").append(options.basePackage()).append(".domain.")
                .append(entityName).append(";\n");
        builder.append("import ").append(options.basePackage()).append(".repository.")
                .append(entityName).append("Repository;\n");
        builder.append("import org.springframework.stereotype.Service;\n");

        if (kinds.contains(CrudShape.Kind.FIND_ALL)) {
            builder.append("import java.util.List;\n");
        }

        for (String value : imports) {
            builder.append("import ").append(value).append(";\n");
        }

        builder.append("\n/**\n");
        builder.append(" * ").append(table.name()).append(" 의 표준 CRUD 입니다.\n");
        builder.append(" *\n");
        builder.append(" * 설계에서 이 표에 연결된 API 중 CRUD 모양인 것만 만들었습니다.\n");
        builder.append(" * 그 밖의 처리는 컨트롤러에 스텁으로 남아 있으니 그쪽을 채워 주세요.\n");
        builder.append(" */\n");
        builder.append("@Service\n");
        builder.append("public class ").append(entityName).append("Service {\n\n");

        builder.append("    private final ").append(entityName).append("Repository ")
                .append(repositoryField).append(";\n\n");

        builder.append("    public ").append(entityName).append("Service(")
                .append(entityName).append("Repository ").append(repositoryField).append(") {\n");
        builder.append("        this.").append(repositoryField).append(" = ")
                .append(repositoryField).append(";\n");
        builder.append("    }\n");

        builder.append(methods);
        builder.append("}\n");

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
}
