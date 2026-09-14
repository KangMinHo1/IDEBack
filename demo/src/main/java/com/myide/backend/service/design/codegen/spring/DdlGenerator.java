package com.myide.backend.service.design.codegen.spring;

import com.myide.backend.dto.design.codegen.GeneratedFile;
import com.myide.backend.dto.design.v2.ColumnV2;
import com.myide.backend.dto.design.v2.DesignModelV2;
import com.myide.backend.dto.design.v2.RelationV2;
import com.myide.backend.dto.design.v2.TableV2;
import com.myide.backend.service.design.codegen.CodegenOptions;
import com.myide.backend.service.design.codegen.CodegenTarget;
import com.myide.backend.service.design.codegen.DesignCodeGenerator;
import com.myide.backend.service.design.codegen.TypeMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ERD 를 그대로 실행 가능한 SQL 로 옮긴다.
 *
 * 외래키는 CREATE TABLE 안에 넣지 않고 전부 뒤쪽 ALTER TABLE 로 모은다.
 * 표를 어떤 순서로 만들어야 하는지 신경 쓸 필요가 없어지고, 표끼리 서로를
 * 가리키는 순환 관계가 있어도 그대로 실행된다.
 */
@Component
public class DdlGenerator implements DesignCodeGenerator {

    @Override
    public boolean supports(CodegenTarget target) {
        return target == CodegenTarget.DDL;
    }

    @Override
    public List<GeneratedFile> generate(DesignModelV2 model, CodegenOptions options) {
        if (model.erd().tables().isEmpty()) {
            return List.of();
        }

        Map<String, TableV2> tableById = new LinkedHashMap<>();
        model.erd().tables().forEach(table -> tableById.put(table.id(), table));

        StringBuilder builder = new StringBuilder();
        builder.append("-- 설계 관리에서 생성된 테이블 정의입니다.\n");
        builder.append("-- 다시 생성하면 이 파일은 덮어써집니다.\n\n");

        for (TableV2 table : model.erd().tables()) {
            if (table.name().isBlank()) {
                continue;
            }
            builder.append(createTable(table)).append("\n");
        }

        String constraints = foreignKeys(model, tableById);
        if (!constraints.isBlank()) {
            builder.append("-- 외래키는 표를 모두 만든 뒤에 겁니다. 만드는 순서를 신경 쓰지 않아도 됩니다.\n");
            builder.append(constraints);
        }

        return List.of(GeneratedFile.shared(
                "src/main/resources/design-schema.sql",
                builder.toString(),
                CodegenTarget.DDL,
                "표 " + model.erd().tables().size() + "개"));
    }

    private String createTable(TableV2 table) {
        StringBuilder builder = new StringBuilder();

        if (!table.description().isBlank()) {
            builder.append("-- ").append(table.description().replace("\n", " ")).append("\n");
        }

        builder.append("CREATE TABLE IF NOT EXISTS `").append(table.name()).append("` (\n");

        List<String> lines = new ArrayList<>();
        List<String> pks = new ArrayList<>();

        for (ColumnV2 column : table.columns()) {
            if (column.name().isBlank()) {
                continue;
            }

            StringBuilder line = new StringBuilder("    `" + column.name() + "` "
                    + TypeMapper.toSqlType(column));

            if (!column.nullable() || column.isPk()) {
                line.append(" NOT NULL");
            }

            if (column.isPk() && isAutoIncrement(column) && countPks(table) == 1) {
                line.append(" AUTO_INCREMENT");
            }

            if (!column.defaultValue().isBlank()) {
                line.append(" DEFAULT ").append(column.defaultValue());
            }

            if (!column.comment().isBlank()) {
                line.append(" COMMENT '").append(column.comment().replace("'", "''")).append("'");
            }

            lines.add(line.toString());

            if (column.isPk()) {
                pks.add("`" + column.name() + "`");
            }
        }

        if (!pks.isEmpty()) {
            lines.add("    PRIMARY KEY (" + String.join(", ", pks) + ")");
        }

        builder.append(String.join(",\n", lines));
        builder.append("\n);\n");

        return builder.toString();
    }

    private String foreignKeys(DesignModelV2 model, Map<String, TableV2> tableById) {
        StringBuilder builder = new StringBuilder();

        for (RelationV2 relation : model.erd().relations()) {
            TableV2 from = tableById.get(relation.fromTableId());
            TableV2 to = tableById.get(relation.toTableId());

            if (from == null || to == null || "N:M".equals(relation.cardinality())) {
                continue;
            }

            ColumnV2 fromColumn = columnOf(from, relation.fromColumnId());
            ColumnV2 toColumn = columnOf(to, relation.toColumnId());

            if (fromColumn == null || toColumn == null) {
                continue;
            }

            builder.append("ALTER TABLE `").append(from.name()).append("`\n");
            builder.append("    ADD CONSTRAINT `fk_").append(from.name()).append("_")
                    .append(fromColumn.name()).append("`\n");
            builder.append("    FOREIGN KEY (`").append(fromColumn.name()).append("`) REFERENCES `")
                    .append(to.name()).append("` (`").append(toColumn.name()).append("`)");

            if (!relation.onDelete().isBlank()) {
                builder.append(" ON DELETE ").append(relation.onDelete().toUpperCase());
            }

            builder.append(";\n\n");
        }

        return builder.toString();
    }

    private ColumnV2 columnOf(TableV2 table, String columnId) {
        return table.columns().stream()
                .filter(column -> column.id().equals(columnId))
                .findFirst()
                .orElse(null);
    }

    private long countPks(TableV2 table) {
        return table.columns().stream().filter(ColumnV2::isPk).count();
    }

    private boolean isAutoIncrement(ColumnV2 column) {
        String type = column.type() == null ? "" : column.type().toUpperCase();
        return type.startsWith("BIGINT") || type.startsWith("INT") || type.startsWith("SMALLINT");
    }
}
