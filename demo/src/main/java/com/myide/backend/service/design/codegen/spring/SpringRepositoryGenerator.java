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
import com.myide.backend.service.design.codegen.Traceability;
import com.myide.backend.service.design.codegen.TypeMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 표마다 Repository 하나.
 *
 * 관계가 있는 표에는 그 관계로 찾는 메서드 시그니처를 미리 넣어 둔다.
 * "이 글의 댓글을 가져온다" 같은 조회는 어차피 반드시 필요한데, 파생 쿼리
 * 이름을 손으로 맞추다 틀리는 일이 잦다.
 */
@Component
public class SpringRepositoryGenerator implements DesignCodeGenerator {

    @Override
    public boolean supports(CodegenTarget target) {
        return target == CodegenTarget.SPRING_REPOSITORY;
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
            String path = options.javaSourceDir() + "/repository/" + entityName + "Repository.java";

            files.add(new GeneratedFile(
                    path,
                    render(model, table, tableById, entityName, options),
                    CodegenTarget.SPRING_REPOSITORY,
                    "테이블 " + table.name(),
                    Traceability.requirementIdsForTable(model, table.id()),
                    Traceability.requirementLabels(model,
                            Traceability.requirementIdsForTable(model, table.id()))));
        }

        return files;
    }

    private String render(DesignModelV2 model, TableV2 table, Map<String, TableV2> tableById,
                          String entityName, CodegenOptions options) {
        String idType = table.columns().stream()
                .filter(ColumnV2::isPk)
                .findFirst()
                .map(TypeMapper::toJavaType)
                .orElse("Long");

        Set<String> imports = new LinkedHashSet<>();
        String simpleIdType = simpleType(idType, imports);

        StringBuilder methods = new StringBuilder();

        for (RelationV2 relation : model.erd().relations()) {
            if (!table.id().equals(relation.fromTableId()) || "N:M".equals(relation.cardinality())) {
                continue;
            }

            TableV2 target = tableById.get(relation.toTableId());
            ColumnV2 fromColumn = findColumn(table, relation.fromColumnId());

            if (target == null || fromColumn == null) {
                continue;
            }

            String targetEntity = NameMapper.toEntityName(target.name(), target.entityName());
            String field = SpringEntityGenerator.associationFieldName(fromColumn.name(), targetEntity);
            String targetIdType = target.columns().stream()
                    .filter(ColumnV2::isPk)
                    .findFirst()
                    .map(TypeMapper::toJavaType)
                    .orElse("Long");

            methods.append("\n    /** ").append(target.name()).append(" 하나에 딸린 ")
                    .append(table.name()).append(" 를 찾는다. */\n");
            methods.append("    List<").append(entityName).append("> findBy")
                    .append(capitalize(field)).append("_Id(")
                    .append(simpleType(targetIdType, imports)).append(" ")
                    .append(NameMapper.toFieldName(field)).append("Id);\n");
        }

        StringBuilder builder = new StringBuilder();
        builder.append("package ").append(options.basePackage()).append(".repository;\n\n");
        builder.append("import ").append(options.basePackage()).append(".domain.")
                .append(entityName).append(";\n");
        builder.append("import org.springframework.data.jpa.repository.JpaRepository;\n");

        if (!methods.isEmpty()) {
            builder.append("import java.util.List;\n");
        }
        for (String value : imports) {
            builder.append("import ").append(value).append(";\n");
        }

        builder.append("\n/**\n");
        builder.append(" * ").append(table.name()).append(" 조회.\n");
        builder.append(" *\n");
        builder.append(" * 설계 관리에서 생성되었습니다.\n");
        builder.append(" */\n");
        builder.append("public interface ").append(entityName).append("Repository extends JpaRepository<")
                .append(entityName).append(", ").append(simpleIdType).append("> {\n");
        builder.append(methods);
        builder.append("}\n");

        return builder.toString();
    }

    private ColumnV2 findColumn(TableV2 table, String columnId) {
        return table.columns().stream()
                .filter(column -> column.id().equals(columnId))
                .findFirst()
                .orElse(null);
    }

    private String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
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
