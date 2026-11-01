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
 * 行级增删改的 SQL 拼装（纯函数，无 IO，方便单测）。
 *
 * <p>同一套拼装给两条链路复用：
 * <ul>
 *   <li>{@code inline=false}：本地直连，值全部走 {@code ?} 占位 + 预编译绑定；</li>
 *   <li>{@code inline=true}：线上小助手不支持占位符，值按列类型转成 SQL 字面量内联（已转义）。</li>
 * </ul>
 *
 * <p>安全约束：表名/列名一律来自结构快照并用 {@link Dialect#quoteAlways} 加引号；
 * 列名不在快照内直接拒绝，不做「猜一个列名」的容错。UPDATE/DELETE 的 WHERE 只允许主键。
 */
public final class RowSqlBuilder {

    private RowSqlBuilder() {
    }

    /** 一条待执行的写语句：sql + 绑定参数（inline 模式下 params 为空）。 */
    public static final class Mutation {
        public final String sql;
        public final List<Object> params;
        public final String kind;

        Mutation(String sql, List<Object> params, String kind) {
            this.sql = sql;
            this.params = params;
            this.kind = kind;
        }
    }

    /** UPDATE：只按主键定位，WHERE 列必须覆盖全部主键。 */
    public static Mutation update(Dialect dialect, TableInfo table, Map<String, Object> pk,
                                  Map<String, Object> changes, boolean inline) {
        List<String> primaryKeys = DbDdlBuilder.primaryColumns(table);
        if (primaryKeys.isEmpty()) {
            throw new IllegalArgumentException("该表没有主键，无法按行修改（只能用 SQL 编辑器）");
        }
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("没有需要提交的修改");
        }
        Map<String, ColumnInfo> columns = columnIndex(table);
        List<Object> params = new ArrayList<>();
        List<String> sets = new ArrayList<>(changes.size());
        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            ColumnInfo column = requireColumn(columns, entry.getKey());
            sets.add(dialect.quoteAlways(column.getName()) + " = " + place(dialect, params, column, entry.getValue(), inline));
        }
        String where = whereClause(dialect, table, primaryKeys, columns, pk, params, inline);
        String sql = "UPDATE " + dialect.quoteAlways(table.getName())
                + " SET " + String.join(", ", sets) + " WHERE " + where;
        return new Mutation(sql, params, "UPDATE");
    }

    /** INSERT：列必须存在于快照；自增列传 null 时直接不写入（交给库生成）。 */
    public static Mutation insert(Dialect dialect, TableInfo table, Map<String, Object> values, boolean inline) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("没有需要写入的字段");
        }
        Map<String, ColumnInfo> columns = columnIndex(table);
        List<Object> params = new ArrayList<>();
        List<String> names = new ArrayList<>(values.size());
        List<String> marks = new ArrayList<>(values.size());
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            ColumnInfo column = requireColumn(columns, entry.getKey());
            if (entry.getValue() == null && Boolean.TRUE.equals(column.getAutoIncrement())) {
                continue;
            }
            names.add(dialect.quoteAlways(column.getName()));
            marks.add(place(dialect, params, column, entry.getValue(), inline));
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("没有需要写入的字段");
        }
        String sql = "INSERT INTO " + dialect.quoteAlways(table.getName())
                + " (" + String.join(", ", names) + ") VALUES (" + String.join(", ", marks) + ")";
        return new Mutation(sql, params, "INSERT");
    }

    /** DELETE：只按主键定位。 */
    public static Mutation delete(Dialect dialect, TableInfo table, Map<String, Object> pk, boolean inline) {
        List<String> primaryKeys = DbDdlBuilder.primaryColumns(table);
        if (primaryKeys.isEmpty()) {
            throw new IllegalArgumentException("该表没有主键，无法按行删除（只能用 SQL 编辑器）");
        }
        Map<String, ColumnInfo> columns = columnIndex(table);
        List<Object> params = new ArrayList<>();
        String where = whereClause(dialect, table, primaryKeys, columns, pk, params, inline);
        String sql = "DELETE FROM " + dialect.quoteAlways(table.getName()) + " WHERE " + where;
        return new Mutation(sql, params, "DELETE");
    }

    /** 主键 WHERE：按主键原始顺序拼接，缺任一主键列即拒绝（防止误伤全表）。 */
    private static String whereClause(Dialect dialect, TableInfo table, List<String> primaryKeys,
                                      Map<String, ColumnInfo> columns, Map<String, Object> pk,
                                      List<Object> params, boolean inline) {
        if (pk == null || pk.isEmpty()) {
            throw new IllegalArgumentException("缺少主键值，无法定位要修改的行");
        }
        // 大小写不敏感地匹配主键列，避免前端传小写键名时漏判
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : pk.entrySet()) {
            if (entry.getKey() != null) {
                normalized.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        List<String> parts = new ArrayList<>(primaryKeys.size());
        for (String key : primaryKeys) {
            String lookup = key.toLowerCase(Locale.ROOT);
            if (!normalized.containsKey(lookup)) {
                throw new IllegalArgumentException("缺少主键列的值: " + key);
            }
            ColumnInfo column = columns.get(lookup);
            parts.add(dialect.quoteAlways(key) + " = " + place(dialect, params, column, normalized.get(lookup), inline));
        }
        return String.join(" AND ", parts);
    }

    private static String place(Dialect dialect, List<Object> params, ColumnInfo column, Object value, boolean inline) {
        if (inline) {
            // MySQL 用反斜杠转义；达梦/神通按标准 SQL（反斜杠是普通字符），不能双写
            return DbValueCodec.literal(column, value, isMysql(dialect));
        }
        params.add(DbValueCodec.coerce(column, value));
        return "?";
    }

    private static boolean isMysql(Dialect dialect) {
        return dialect != null && "mysql".equalsIgnoreCase(dialect.name());
    }

    private static ColumnInfo requireColumn(Map<String, ColumnInfo> columns, String name) {
        ColumnInfo column = name == null ? null : columns.get(name.toLowerCase(Locale.ROOT));
        if (column == null) {
            throw new IllegalArgumentException("字段不存在或无权访问: " + name);
        }
        if (!DbValueCodec.editable(column)) {
            throw new IllegalArgumentException("字段 " + column.getName() + " 是二进制/大对象类型，暂不支持在网格中编辑");
        }
        return column;
    }

    /** 列名小写索引（达梦/神通大小写敏感，但前端回传的列名以快照为准，这里只做容错匹配）。 */
    public static Map<String, ColumnInfo> columnIndex(TableInfo table) {
        Map<String, ColumnInfo> index = new LinkedHashMap<>();
        if (table == null || table.getColumns() == null) {
            return index;
        }
        for (ColumnInfo column : table.getColumns()) {
            index.put(column.getName().toLowerCase(Locale.ROOT), column);
        }
        return index;
    }
}
