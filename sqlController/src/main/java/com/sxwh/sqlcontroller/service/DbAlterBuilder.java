package com.sxwh.sqlcontroller.service;

import com.sxwh.sqlcontroller.dialect.Dialect;
import com.sxwh.sqlcontroller.model.ColumnInfo;
import com.sxwh.sqlcontroller.model.TableInfo;
import com.sxwh.sqlcontroller.util.DbValueCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 结构编辑的 DDL 生成（纯函数，无 IO）。
 *
 * <p>输入是「操作清单」（前端字段表改了什么就传什么），输出是按方言展开的语句序列 + 提示。
 * 三种方言的语法差异集中在这里：
 * <ul>
 *   <li>MySQL：一条 {@code ALTER TABLE ... MODIFY COLUMN} 就能同时改类型/可空/默认值/注释；</li>
 *   <li>达梦：改类型、改可空、改注释必须拆成三条语句（注释走 {@code COMMENT ON COLUMN}）；</li>
 *   <li>神通（PG 系）：{@code ALTER COLUMN ... TYPE / SET NOT NULL / SET DEFAULT} 逐项拆开。</li>
 * </ul>
 *
 * <p>安全：类型/默认值只做「拒绝分号与注释符」的兜底校验，不尝试解析 SQL；实际执行前仍会弹确认框展示完整语句。
 */
public final class DbAlterBuilder {

    private DbAlterBuilder() {
    }

    /** 单条语句 + 给人看的说明（前端作为告警展示）。 */
    public static final class Statement {
        public final String sql;
        public final String note;

        Statement(String sql, String note) {
            this.sql = sql;
            this.note = note;
        }
    }

    /** 生成结果：语句序列 + 告警。 */
    public static final class Plan {
        public final List<Statement> statements = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
    }

    /**
     * 生成 DDL。
     *
     * @param table 结构快照（用于校验列是否存在、合并未显式给出的属性）
     * @param ops   操作清单
     * @param mysql 是否 MySQL 家族（线上全是 MySQL；达梦/神通走 false）
     */
    public static Plan build(TableInfo table, Dialect dialect, boolean mysql, List<Map<String, Object>> ops) {
        Plan plan = new Plan();
        if (ops == null || ops.isEmpty()) {
            plan.warnings.add("没有检测到结构改动");
            return plan;
        }
        Map<String, ColumnInfo> existing = RowSqlBuilder.columnIndex(table);
        String tableName = dialect.quoteAlways(table.getName());
        for (Map<String, Object> op : ops) {
            String type = str(op.get("type"));
            if (type == null) {
                continue;
            }
            switch (type.toLowerCase(Locale.ROOT)) {
                case "modify":
                    modify(plan, table, tableName, existing, dialect, mysql, op);
                    break;
                case "add":
                    add(plan, tableName, existing, dialect, mysql, op);
                    break;
                case "drop":
                    drop(plan, tableName, existing, dialect, op);
                    break;
                case "index-add":
                    indexAdd(plan, table, tableName, dialect, mysql, op);
                    break;
                case "index-drop":
                    indexDrop(plan, tableName, dialect, mysql, op);
                    break;
                default:
                    plan.warnings.add("未知操作已忽略: " + type);
            }
        }
        return plan;
    }

    // ==================== 改字段 ====================

    private static void modify(Plan plan, TableInfo table, String tableName, Map<String, ColumnInfo> existing,
                               Dialect dialect, boolean mysql, Map<String, Object> op) {
        String name = str(op.get("column"));
        ColumnInfo before = name == null ? null : existing.get(name.toLowerCase(Locale.ROOT));
        if (before == null) {
            throw new IllegalArgumentException("字段不存在，无法修改: " + name);
        }
        String columnName = dialect.quoteAlways(before.getName());
        String columnType = type(op.get("dataType"), before.getType());
        Boolean nullable = bool(op.containsKey("nullable") ? op.get("nullable") : before.getNullable());
        String defaultValue = op.containsKey("defaultValue") ? str(op.get("defaultValue")) : before.getDefaultValue();
        String comment = op.containsKey("comment") ? str(op.get("comment")) : before.getComment();

        if (mysql) {
            StringBuilder sql = new StringBuilder("ALTER TABLE ").append(tableName)
                    .append(" MODIFY COLUMN ").append(columnName).append(" ").append(columnType);
            sql.append(Boolean.FALSE.equals(nullable) ? " NOT NULL" : " NULL");
            if (notEmpty(defaultValue)) {
                sql.append(" DEFAULT ").append(expression(defaultValue));
            }
            if (Boolean.TRUE.equals(before.getAutoIncrement())) {
                sql.append(" AUTO_INCREMENT");
            }
            if (notEmpty(comment)) {
                sql.append(" COMMENT '").append(DbValueCodec.escape(comment)).append("'");
            }
            plan.statements.add(new Statement(sql.toString(), "改字段 " + before.getName()));
            return;
        }

        // 达梦 / 神通：类型、可空、默认值、注释分语句
        boolean typeChanged = !equalsIgnoreCase(columnType, before.getType());
        boolean nullableChanged = nullable != null && !nullable.equals(before.getNullable());
        boolean defaultChanged = !equalsNullable(defaultValue, before.getDefaultValue());
        boolean commentChanged = !equalsNullable(comment, before.getComment());

        if (typeChanged || defaultChanged) {
            if (mysqlLikeDm(dialect)) {
                StringBuilder sql = new StringBuilder("ALTER TABLE ").append(tableName)
                        .append(" MODIFY ").append(columnName).append(" ").append(columnType);
                if (notEmpty(defaultValue)) {
                    sql.append(" DEFAULT ").append(expression(defaultValue));
                }
                plan.statements.add(new Statement(sql.toString(), "改字段类型 " + before.getName()));
                if (typeChanged) {
                    plan.warnings.add("达梦修改字段类型可能重写整表数据，大表请先评估耗时与锁表影响");
                }
            } else {
                if (typeChanged) {
                    plan.statements.add(new Statement("ALTER TABLE " + tableName
                            + " ALTER COLUMN " + columnName + " TYPE " + columnType, "改字段类型 " + before.getName()));
                }
                if (defaultChanged) {
                    plan.statements.add(new Statement("ALTER TABLE " + tableName + " ALTER COLUMN " + columnName
                            + (notEmpty(defaultValue) ? " SET DEFAULT " + expression(defaultValue) : " DROP DEFAULT"),
                            "改默认值 " + before.getName()));
                }
            }
        }
        if (nullableChanged) {
            plan.statements.add(new Statement(mysqlLikeDm(dialect)
                    ? "ALTER TABLE " + tableName + " MODIFY " + columnName + (Boolean.FALSE.equals(nullable) ? " NOT NULL" : " NULL")
                    : "ALTER TABLE " + tableName + " ALTER COLUMN " + columnName
                    + (Boolean.FALSE.equals(nullable) ? " SET NOT NULL" : " DROP NOT NULL"),
                    "改可空性 " + before.getName()));
        }
        if (commentChanged) {
            plan.statements.add(new Statement(dialect.commentOnColumn(tableName, columnName,
                    comment == null ? "" : comment), "改注释 " + before.getName()));
        }
    }

    // ==================== 加字段 ====================

    private static void add(Plan plan, String tableName, Map<String, ColumnInfo> existing,
                            Dialect dialect, boolean mysql, Map<String, Object> op) {
        String name = str(op.get("column"));
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("新字段名不能为空");
        }
        if (existing.containsKey(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("字段已存在: " + name);
        }
        String columnName = dialect.quoteAlways(name);
        String columnType = type(op.get("dataType"), null);
        Boolean nullable = bool(op.get("nullable"));
        String defaultValue = str(op.get("defaultValue"));
        String comment = op.containsKey("comment") ? str(op.get("comment")) : null;
        String after = str(op.get("after"));

        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(tableName).append(" ADD COLUMN ");
        sql.append(columnName).append(" ").append(columnType);
        if (Boolean.FALSE.equals(nullable)) {
            sql.append(" NOT NULL");
        }
        if (notEmpty(defaultValue)) {
            sql.append(" DEFAULT ").append(expression(defaultValue));
        }
        if (mysql && notEmpty(comment)) {
            sql.append(" COMMENT '").append(DbValueCodec.escape(comment)).append("'");
        }
        if (mysql && notEmpty(after)) {
            sql.append(" AFTER ").append(dialect.quoteAlways(after));
        }
        plan.statements.add(new Statement(sql.toString(), "新增字段 " + name));
        if (!mysql && notEmpty(comment)) {
            plan.statements.add(new Statement(dialect.commentOnColumn(tableName, columnName, comment), "新增字段注释 " + name));
        }
    }

    // ==================== 删字段 ====================

    private static void drop(Plan plan, String tableName, Map<String, ColumnInfo> existing,
                             Dialect dialect, Map<String, Object> op) {
        String name = str(op.get("column"));
        ColumnInfo column = name == null ? null : existing.get(name.toLowerCase(Locale.ROOT));
        if (column == null) {
            throw new IllegalArgumentException("字段不存在，无法删除: " + name);
        }
        if (Boolean.TRUE.equals(column.getPrimaryKey())) {
            throw new IllegalArgumentException("主键字段不能直接删除，请先在库内调整主键: " + column.getName());
        }
        plan.statements.add(new Statement("ALTER TABLE " + tableName + " DROP COLUMN "
                + dialect.quoteAlways(column.getName()), "删除字段 " + column.getName()));
        plan.warnings.add("删除字段会一并丢弃该列数据，且不可回滚，请确认已备份");
    }

    // ==================== 索引 ====================

    private static void indexAdd(Plan plan, TableInfo table, String tableName, Dialect dialect,
                                 boolean mysql, Map<String, Object> op) {
        List<String> columns = strings(op.get("columns"));
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("新增索引必须至少选择一列");
        }
        List<String> quoted = new ArrayList<>(columns.size());
        Map<String, ColumnInfo> existing = RowSqlBuilder.columnIndex(table);
        for (String column : columns) {
            ColumnInfo info = existing.get(column.toLowerCase(Locale.ROOT));
            if (info == null) {
                throw new IllegalArgumentException("索引列不存在: " + column);
            }
            quoted.add(dialect.quoteAlways(info.getName()));
        }
        String indexName = str(op.get("name"));
        boolean unique = Boolean.TRUE.equals(bool(op.get("unique")));
        if (mysql) {
            StringBuilder sql = new StringBuilder("ALTER TABLE ").append(tableName)
                    .append(" ADD ").append(unique ? "UNIQUE " : "").append("INDEX ");
            if (notEmpty(indexName)) {
                sql.append(dialect.quoteAlways(indexName)).append(" ");
            }
            sql.append("(").append(String.join(", ", quoted)).append(")");
            plan.statements.add(new Statement(sql.toString(), "新增索引"));
        } else {
            if (!notEmpty(indexName)) {
                throw new IllegalArgumentException("该数据库新增索引必须指定索引名");
            }
            plan.statements.add(new Statement("CREATE " + (unique ? "UNIQUE " : "") + "INDEX "
                    + dialect.quoteAlways(indexName) + " ON " + tableName
                    + " (" + String.join(", ", quoted) + ")", "新增索引 " + indexName));
        }
    }

    private static void indexDrop(Plan plan, String tableName, Dialect dialect, boolean mysql, Map<String, Object> op) {
        String indexName = str(op.get("name"));
        if (!notEmpty(indexName)) {
            throw new IllegalArgumentException("请指定要删除的索引名");
        }
        if ("primary".equalsIgnoreCase(indexName) || "PRIMARY".equals(indexName)) {
            throw new IllegalArgumentException("主键索引不能在这里删除");
        }
        plan.statements.add(new Statement(mysql
                ? "ALTER TABLE " + tableName + " DROP INDEX " + dialect.quoteAlways(indexName)
                : "DROP INDEX " + dialect.quoteAlways(indexName), "删除索引 " + indexName));
    }

    // ==================== 工具 ====================

    /** 达梦的改列语法与 MySQL 接近（MODIFY xxx），神通是 PG 风格（ALTER COLUMN）。 */
    private static boolean mysqlLikeDm(Dialect dialect) {
        return dialect != null && "dm".equalsIgnoreCase(dialect.name());
    }

    private static String type(Object value, String fallback) {
        String text = str(value);
        if (!notEmpty(text)) {
            text = fallback;
        }
        if (!notEmpty(text)) {
            throw new IllegalArgumentException("字段类型不能为空");
        }
        String trimmed = text.trim();
        guard(trimmed, "字段类型");
        return trimmed;
    }

    /** 默认值既可以是字面量（0、''、CURRENT_TIMESTAMP），也会被原样拼进 DDL，因此只拦明显的语句拼接。 */
    private static String expression(String value) {
        String trimmed = value.trim();
        guard(trimmed, "默认值");
        return trimmed;
    }

    private static void guard(String value, String label) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains(";") || lower.contains("--") || lower.contains("/*")) {
            throw new IllegalArgumentException(label + "中不允许出现分号或注释符：" + value);
        }
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left == null ? right == null : right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private static boolean equalsNullable(String left, String right) {
        String a = left == null ? null : left.trim();
        String b = right == null ? null : right.trim();
        return a == null ? b == null || b.isEmpty() : a.equals(b);
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean bool(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        return "1".equals(text) || "true".equals(text) || "y".equals(text) || "yes".equals(text);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    /** 供服务层把内部结构转成前端友好的 Map。 */
    public static Map<String, Object> describe(Plan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> statements = new ArrayList<>(plan.statements.size());
        for (Statement statement : plan.statements) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sql", statement.sql);
            item.put("note", statement.note);
            statements.add(item);
        }
        out.put("statements", statements);
        out.put("warnings", plan.warnings);
        return out;
    }
}
