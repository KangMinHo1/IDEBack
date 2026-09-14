package com.myide.backend.service.design.codegen.spring;

import com.myide.backend.dto.design.codegen.GeneratedFile;
import com.myide.backend.dto.design.v2.ColumnV2;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.RelationV2;
import com.myide.backend.dto.design.v2.TableV2;
import com.myide.backend.service.design.codegen.CodegenOptions;
import com.myide.backend.service.design.codegen.CodegenTarget;
import com.myide.backend.service.design.codegen.DesignCodeGenerator;
import com.myide.backend.service.design.codegen.NameMapper;
import com.myide.backend.service.design.codegen.TypeMapper;
import com.myide.backend.service.design.codegen.Traceability;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ERD 의 표 하나를 JPA Entity 한 개로 옮긴다.
 *
 * 코드 모양은 이 저장소의 기존 도메인 클래스(domain/design/DesignDocument)를
 * 그대로 따랐다. 팀이 읽던 코드와 다른 모양이 섞이면 생성된 코드만 이물감이
 * 든다.
 */
@Component
public class SpringEntityGenerator implements DesignCodeGenerator {

    @Override
    public boolean supports(CodegenTarget target) {
        return target == CodegenTarget.SPRING_ENTITY;
    }

    @Override
    public List<GeneratedFile> generate(DesignModelV2 model, CodegenOptions options) {
        List<GeneratedFile> files = new ArrayList<>();
        Map<String, TableV2> tableById = new LinkedHashMap<>();
        model.erd().tables().forEach(table -> tableById.put(table.id(), table));

        for (TableV2 table : model.erd().tables()) {
            if (table.name().isBlank()) {
                continue;
            }

            String entityName = NameMapper.toEntityName(table.name(), table.entityName());
            String path = options.javaSourceDir() + "/domain/" + entityName + ".java";

            files.add(new GeneratedFile(
                    path,
                    render(model, table, tableById, entityName, options),
                    CodegenTarget.SPRING_ENTITY,
                    "테이블 " + table.name(),
                    Traceability.requirementIdsForTable(model, table.id()),
                    Traceability.requirementLabels(model,
                            Traceability.requirementIdsForTable(model, table.id()))));
        }

        return files;
    }

    private String render(DesignModelV2 model, TableV2 table, Map<String, TableV2> tableById,
                          String entityName, CodegenOptions options) {
        Set<String> imports = new LinkedHashSet<>();
        StringBuilder fields = new StringBuilder();

        // 관계가 걸린 컬럼은 값이 아니라 연관 필드로 나가야 한다. 두 번 쓰지
        // 않도록 먼저 골라 둔다.
        Map<String, RelationV2> relationByColumnId = new LinkedHashMap<>();
        for (RelationV2 relation : model.erd().relations()) {
            if (!table.id().equals(relation.fromTableId())) {
                continue;
            }
            if ("N:M".equals(relation.cardinality())) {
                continue;
            }
            if (tableById.containsKey(relation.toTableId()) && !relation.fromColumnId().isBlank()) {
                relationByColumnId.put(relation.fromColumnId(), relation);
            }
        }

        List<ColumnV2> pks = table.columns().stream().filter(ColumnV2::isPk).toList();
        boolean first = true;

        for (ColumnV2 column : table.columns()) {
            if (column.name().isBlank()) {
                continue;
            }

            if (!first) {
                fields.append("\n");
            }
            first = false;

            RelationV2 relation = relationByColumnId.get(column.id());

            if (relation != null) {
                fields.append(association(column, relation, tableById));
            } else {
                fields.append(scalar(column, pks, imports));
            }
        }

        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(options.basePackage()).append(".domain;\n\n");
        builder.append("import jakarta.persistence.*;\n");
        builder.append("import lombok.*;\n");

        for (String value : imports) {
            builder.append("import ").append(value).append(";\n");
        }

        builder.append("\n");
        builder.append(javadoc(model, table, pks));
        builder.append("@Entity\n");
        builder.append("@Table(name = \"").append(table.name()).append("\")\n");
        builder.append("@Getter\n@Setter\n@NoArgsConstructor\n@AllArgsConstructor\n@Builder\n");
        builder.append("public class ").append(entityName).append(" {\n\n");
        builder.append(fields);
        builder.append("}\n");

        return builder.toString();
    }

    private String javadoc(DesignModelV2 model, TableV2 table, List<ColumnV2> pks) {
        StringBuilder builder = new StringBuilder("/**\n");

        builder.append(" * ").append(table.description().isBlank()
                ? table.name() + " 테이블"
                : table.description().replace("*/", "*")).append("\n");
        builder.append(" *\n");

        List<String> requirements = Traceability.requirementLabelsForTable(model, table.id());
        if (!requirements.isEmpty()) {
            builder.append(" * 관련 요구사항: ").append(String.join(", ", requirements)).append("\n");
        }

        if (pks.size() > 1) {
            builder.append(" * TODO: 기본키가 여러 개입니다. @IdClass 또는 @EmbeddedId 로 바꿔 주세요.\n");
        }

        builder.append(" *\n");
        builder.append(" * 설계 관리에서 생성되었습니다. 직접 고쳐도 되지만, 다시 생성하면 덮어씁니다.\n");
        builder.append(" */\n");

        return builder.toString();
    }

    private String scalar(ColumnV2 column, List<ColumnV2> pks, Set<String> imports) {
        StringBuilder builder = new StringBuilder();

        if (!column.comment().isBlank()) {
            builder.append("    /** ").append(column.comment().replace("*/", "*")).append(" */\n");
        }

        boolean isPrimary = column.isPk() && !pks.isEmpty() && pks.get(0).id().equals(column.id());

        if (isPrimary) {
            builder.append("    @Id\n");
            if (isAutoIncrement(column)) {
                builder.append("    @GeneratedValue(strategy = GenerationType.IDENTITY)\n");
            }
        }

        builder.append("    @Column(name = \"").append(column.name()).append("\"");

        if (!column.nullable() && !isPrimary) {
            builder.append(", nullable = false");
        }
        if (TypeMapper.hasJpaLength(column)) {
            builder.append(", length = ").append(column.length());
        }

        String definition = TypeMapper.columnDefinition(column);
        if (!definition.isBlank()) {
            builder.append(", columnDefinition = \"").append(definition).append("\"");
        }

        builder.append(")\n");

        String javaType = TypeMapper.toJavaType(column);
        builder.append("    private ").append(simpleType(javaType, imports)).append(" ")
                .append(NameMapper.toFieldName(column.name())).append(";\n");

        return builder.toString();
    }

    private String association(ColumnV2 column, RelationV2 relation, Map<String, TableV2> tableById) {
        TableV2 target = tableById.get(relation.toTableId());
        String targetEntity = NameMapper.toEntityName(target.name(), target.entityName());

        StringBuilder builder = new StringBuilder();
        builder.append("    /** ").append(relation.note().isBlank()
                ? column.name() + " → " + target.name()
                : relation.note().replace("*/", "*")).append(" */\n");

        builder.append("1:1".equals(relation.cardinality())
                ? "    @OneToOne(fetch = FetchType.LAZY)\n"
                : "    @ManyToOne(fetch = FetchType.LAZY)\n");

        builder.append("    @JoinColumn(name = \"").append(column.name()).append("\"");
        if (!column.nullable()) {
            builder.append(", nullable = false");
        }
        builder.append(")\n");

        builder.append("    private ").append(targetEntity).append(" ")
                .append(associationFieldName(column.name(), targetEntity)).append(";\n");

        return builder.toString();
    }

    /** author_id → author. 뒤의 _id 를 떼면 사람이 부르는 이름이 된다. */
    static String associationFieldName(String columnName, String targetEntity) {
        String base = columnName;

        if (base.toLowerCase().endsWith("_id")) {
            base = base.substring(0, base.length() - 3);
        } else if (base.length() > 2 && base.toLowerCase().endsWith("id")) {
            base = base.substring(0, base.length() - 2);
        }

        return base.isBlank()
                ? NameMapper.toCamelCase(targetEntity)
                : NameMapper.toFieldName(base);
    }

    private boolean isAutoIncrement(ColumnV2 column) {
        String type = column.type() == null ? "" : column.type().toUpperCase();
        return type.startsWith("BIGINT") || type.startsWith("INT") || type.startsWith("SMALLINT");
    }

    /** java.time.LocalDate 처럼 온 타입을 import 로 옮기고 짧은 이름만 남긴다. */
    private String simpleType(String javaType, Set<String> imports) {
        int lastDot = javaType.lastIndexOf('.');

        if (lastDot < 0) {
            return javaType;
        }

        imports.add(javaType);
        return javaType.substring(lastDot + 1);
    }
}
