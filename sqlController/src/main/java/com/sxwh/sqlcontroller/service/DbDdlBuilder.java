package com.sxwh.sqlcontroller.service;

import com.sxwh.sqlcontroller.dialect.Dialect;
import com.sxwh.sqlcontroller.model.ColumnInfo;
import com.sxwh.sqlcontroller.model.IndexInfo;
import com.sxwh.sqlcontroller.model.TableInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 按结构快照拼装建表 DDL（表浏览「结构」页展示用）。
 *
 * <p>只用于展示与人工复制，不参与执行链路；因此名称/类型/默认值全部按库内原样输出，
 * 不做跨方言翻译（翻译是 {@code SqlConvertService} 的职责）。
 * MySQL 输出反引号 + 内联 COMMENT，达梦/神通输出双引号 + COMMENT ON 语句。
 */
public final class DbDdlBuilder {

    private DbDdlBuilder() {
    }

    /** 拼装 CREATE TABLE（必要时追加注释语句）。 */
    public static String build(TableInfo table, Dialect dialect, boolean mysql) {
        if (table == null) {
            return "";
        }
        String name = dialect.quoteAlways(table.getName());
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(name).append(" (\n");

        List<String> parts = new ArrayList<>();
        for (ColumnInfo column : table.getColumns()) {
            parts.add("  " + columnLine(column, dialect, mysql));
        }
        List<String> pkColumns = primaryColumns(table);
        if (!pkColumns.isEmpty()) {
            if (mysql) {
                parts.add("  PRIMARY KEY (" + joinQuoted(pkColumns, dialect) + ")");
            } else {
                parts.add("  CONSTRAINT " + dialect.quoteAlways("PK_" + table.getName()) + " PRIMARY KEY (" + joinQuoted(pkColumns, dialect) + ")");
            }
        }
        if (mysql) {
            for (IndexInfo index : table.getIndexes()) {
                if (index.isPrimary() || index.getColumns() == null || index.getColumns().isEmpty()) {
                    continue;
                }
                parts.add("  " + (index.isUnique() ? "UNIQUE KEY " : "KEY ") + dialect.quoteAlways(index.getName())
                        + " (" + joinQuoted(index.getColumns(), dialect) + ")");
            }
        }
        ddl.append(String.join(",\n", parts)).append("\n)");
        if (mysql) {
            if (notEmpty(table.getComment())) {
                ddl.append(" COMMENT='").append(escape(table.getComment())).append("'");
            }
            ddl.append(";");
            return ddl.toString();
        }

        ddl.append(";");
        if (notEmpty(table.getComment())) {
            ddl.append("\n").append(dialect.commentOnTable(name, table.getComment())).append(";");
        }
        for (ColumnInfo column : table.getColumns()) {
            if (notEmpty(column.getComment())) {
                ddl.append("\n").append(dialect.commentOnColumn(name, dialect.quoteAlways(column.getName()), column.getComment())).append(";");
            }
        }
        for (IndexInfo index : table.getIndexes()) {
            if (index.isPrimary() || index.getColumns() == null || index.getColumns().isEmpty()) {
                continue;
            }
            ddl.append("\nCREATE ").append(index.isUnique() ? "UNIQUE " : "").append("INDEX ")
                    .append(dialect.quoteAlways(index.getName())).append(" ON ").append(name)
                    .append(" (").append(joinQuoted(index.getColumns(), dialect)).append(");");
        }
        return ddl.toString();
    }

    /** 单列定义：名称 类型 [NOT NULL] [DEFAULT x] [AUTO_INCREMENT / 内联注释]。 */
    private static String columnLine(ColumnInfo column, Dialect dialect, boolean mysql) {
        StringBuilder line = new StringBuilder();
        line.append(dialect.quoteAlways(column.getName())).append(" ");
        line.append(column.getType() == null || column.getType().trim().isEmpty() ? "VARCHAR(255)" : column.getType().trim());
        if (Boolean.FALSE.equals(column.getNullable())) {
            line.append(" NOT NULL");
        }
        if (notEmpty(column.getDefaultValue())) {
            line.append(" DEFAULT ").append(column.getDefaultValue().trim());
        }
        if (Boolean.TRUE.equals(column.getAutoIncrement())) {
            // MySQL 是列属性；达梦/神通的自增已经体现在快照返回的类型里（IDENTITY / SERIAL），不再追加
            if (mysql) {
                line.append(" AUTO_INCREMENT");
            }
        }
        if (mysql && notEmpty(column.getComment())) {
            line.append(" COMMENT '").append(escape(column.getComment())).append("'");
        }
        return line.toString();
    }

    /** 主键列（快照里 primaryKey=true 的列，按原始顺序）。 */
    public static List<String> primaryColumns(TableInfo table) {
        List<String> keys = new ArrayList<>();
        if (table == null || table.getColumns() == null) {
            return keys;
        }
        for (ColumnInfo column : table.getColumns()) {
            if (Boolean.TRUE.equals(column.getPrimaryKey())) {
                keys.add(column.getName());
            }
        }
        return keys;
    }

    private static String joinQuoted(List<String> names, Dialect dialect) {
        List<String> quoted = new ArrayList<>(names.size());
        for (String name : names) {
            quoted.add(dialect.quoteAlways(name));
        }
        return String.join(", ", quoted);
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("'", "''").replace("\r", " ").replace("\n", " ");
    }

    /** 是否 MySQL 家族（决定用反引号还是双引号 + 内联注释）。 */
    public static boolean isMysql(String dbType) {
        return dbType != null && "mysql".equals(dbType.toLowerCase(Locale.ROOT));
    }
}
