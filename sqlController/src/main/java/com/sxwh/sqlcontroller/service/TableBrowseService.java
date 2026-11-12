package com.sxwh.sqlcontroller.service;

import com.sxwh.sqlcontroller.cache.SchemaCache;
import com.sxwh.sqlcontroller.config.AutocompleteProperties;
import com.sxwh.sqlcontroller.dialect.Dialect;
import com.sxwh.sqlcontroller.dialect.DialectRegistry;
import com.sxwh.sqlcontroller.model.ColumnInfo;
import com.sxwh.sqlcontroller.model.DatabaseConnection;
import com.sxwh.sqlcontroller.model.IndexInfo;
import com.sxwh.sqlcontroller.model.SchemaSnapshot;
import com.sxwh.sqlcontroller.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 表浏览服务：表清单 / 单表结构 + DDL / 表数据分页 / CSV 导出。
 *
 * <p>复用现有能力，不新造执行链路：
 * <ul>
 *   <li>结构：{@link SchemaService#snapshot(Long, boolean, List)} 的 tableNames 分支（跳过 24h 缓存）</li>
 *   <li>数据：{@link DynamicDataSourceService#execute}（方言分页 + COUNT 下推）</li>
 *   <li>导出：{@link DynamicDataSourceService#executeForExport}（不分页、不 COUNT）</li>
 * </ul>
 *
 * <p>安全约束：表名/列名必须存在于当前连接的结构快照中（白名单），值一律预编译绑定。
 */
@Service
public class TableBrowseService {

    private static final Logger log = LoggerFactory.getLogger(TableBrowseService.class);

    /** 导出上限：防止误点把百万行表拉爆内存。 */
    private static final int MAX_EXPORT_ROWS = 50000;
    /** 模糊搜索最多参与拼接的文本列数量。 */
    private static final int MAX_KEYWORD_COLUMNS = 10;

    private final DatabaseConnectionService connections;
    private final DynamicDataSourceService executor;
    private final SchemaService schemas;
    private final DialectRegistry dialects;
    private final AutocompleteProperties props;

    /** 表清单缓存：connectionId → 全量清单，TTL 与单表结构一致（默认永不过期）。 */
    private final SchemaCache<List<Map<String, Object>>> listCache;

    public TableBrowseService(DatabaseConnectionService connections, DynamicDataSourceService executor,
                              SchemaService schemas, DialectRegistry dialects, AutocompleteProperties props) {
        this.connections = connections;
        this.executor = executor;
        this.schemas = schemas;
        this.dialects = dialects;
        this.props = props;
        this.listCache = new SchemaCache<>(props::getTableCacheSeconds, Math.max(1, props.getTableCacheMaxEntries()));
    }

    // ==================== 表清单 ====================

    /**
     * 轻量表清单（只查表名 + 表注释，不扫列）。
     *
     * <p>清单按连接整份缓存（TTL 与单表结构一致，默认永不过期），keyword 只过滤缓存结果，
     * 不按关键字存多份；缓存失效入口是多级刷新 {@code /cache/refresh}。
     */
    public List<Map<String, Object>> tables(Long connectionId, String keyword) {
        DatabaseConnection conn = requireConnection(connectionId);
        List<Map<String, Object>> all = cachedTableList(connectionId, conn);
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (kw.isEmpty()) {
            return all;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : all) {
            if (String.valueOf(item.get("name")).toLowerCase(Locale.ROOT).contains(kw)) {
                out.add(item);
            }
        }
        return out;
    }

    /** 取整份清单：命中缓存直接返回，未命中才真查并回填。 */
    private List<Map<String, Object>> cachedTableList(Long connectionId, DatabaseConnection conn) {
        return listCache.getOrLoad(String.valueOf(connectionId),
                () -> Collections.unmodifiableList(loadAllTables(conn)));
    }

    /**
     * 真查表清单。方言字典查询失败时回退到整库结构快照（首次慢、之后走 24h 缓存）。
     */
    private List<Map<String, Object>> loadAllTables(DatabaseConnection conn) {
        Dialect dialect = dialects.get(conn.getDbType());
        String schema = dialect.schemaName(conn);

        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Map<String, Object> raw = executor.executeRaw(conn, dialect.schemaAllTableCommentsQuery(),
                    Collections.singletonList(schema));
            for (Map<?, ?> row : rowMaps(raw)) {
                String name = SchemaService.str(row, "table_name");
                if (name == null || SchemaService.isSystemTable(name)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("comment", commentOf(row));
                out.add(item);
            }
        } catch (Exception e) {
            log.warn("[TableBrowse] 方言表清单查询失败，回退结构快照 connectionId={} dbType={}: {}",
                    conn.getId(), conn.getDbType(), e.getMessage());
            out.clear();
            SchemaSnapshot snapshot = schemas.snapshot(conn.getId(), false);
            if (!snapshot.isOk()) {
                throw new IllegalStateException("读取表清单失败: " + snapshot.getError());
            }
            for (TableInfo table : snapshot.getTables()) {
                if (SchemaService.isSystemTable(table.getName())) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", table.getName());
                item.put("comment", table.getComment());
                out.add(item);
            }
        }
        out.sort(Comparator.comparing(item -> String.valueOf(item.get("name"))));
        return out;
    }

    // ==================== 单表结构 + DDL ====================

    /**
     * 单表结构：列（含主键/自增/可空/默认值/注释）+ 索引 + 主键列 + 展示用 DDL。
     *
     * @param refresh true = 跳过缓存强制重查（「刷新表结构」按钮）；false = 命中缓存直接返回
     */
    public Map<String, Object> table(Long connectionId, String table, boolean refresh) {
        DatabaseConnection conn = requireConnection(connectionId);
        TableInfo info = requireTable(conn, table, refresh);
        Dialect dialect = dialects.get(conn.getDbType());
        boolean mysql = DbDdlBuilder.isMysql(conn.getDbType());

        String ddl = null;
        String ddlSource = "generated";
        if (mysql) {
            try {
                ddl = showCreateTable(conn, dialect, info.getName());
                ddlSource = "native";
            } catch (Exception e) {
                log.warn("[TableBrowse] SHOW CREATE TABLE 失败将按结构拼装 connectionId={} table={}: {}",
                        connectionId, info.getName(), e.getMessage());
            }
        }
        if (ddl == null || ddl.trim().isEmpty()) {
            ddl = DbDdlBuilder.build(info, dialect, mysql);
            ddlSource = "generated";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connectionId", connectionId);
        out.put("dbType", conn.getDbType());
        out.put("databaseName", conn.getDatabaseName());
        out.put("regionName", conn.getRegionName());
        out.put("name", info.getName());
        out.put("comment", info.getComment());
        out.put("columns", columns(info));
        out.put("indexes", indexes(info));
        out.put("primaryKeys", DbDdlBuilder.primaryColumns(info));
        out.put("ddl", ddl);
        out.put("ddlSource", ddlSource);
        out.put("fetchedAt", System.currentTimeMillis());
        // 结构缓存抓取时间：永久缓存下前端要显示「这份结构有多旧」，用户才知道该不该刷新
        out.put("structureCachedAt", schemas.tableCacheCapturedAt(connectionId, info.getName()));
        return out;
    }

    // ==================== 表数据分页 ====================

    /**
     * 按表分页取数。
     *
     * <p>请求体：{@code {connectionId, table, page, pageSize, orderBy:{column,desc}, filters:[{column,op,value}], keyword}}。
     * 排序规则：显式 orderBy 优先；否则按主键升序（保证 DM/神通 ROW_NUMBER 分页稳定）；
     * 无主键表不排序，并在响应里标记 unstable=true、editable=false。
     */
    public Map<String, Object> rows(Map<String, Object> body) {
        DatabaseConnection conn = requireConnection(asLong(body.get("connectionId")));
        String tableName = asString(body.get("table"));
        TableInfo info = requireTable(conn, tableName);
        Dialect dialect = dialects.get(conn.getDbType());

        int page = Math.max(1, (int) asLong(body.get("page"), 1));
        int pageSize = (int) asLong(body.get("pageSize"), 50);
        pageSize = Math.min(Math.max(1, pageSize), 100);

        List<String> primaryKeys = DbDdlBuilder.primaryColumns(info);
        Map<String, ColumnInfo> columnIndex = columnIndex(info);

        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(dialect.quoteAlways(info.getName()));
        List<Object> parameters = new ArrayList<>();

        List<String> conditions = new ArrayList<>();
        appendFilters(conditions, parameters, body.get("filters"), columnIndex, dialect);
        appendKeyword(conditions, parameters, asString(body.get("keyword")), info, dialect);
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }

        String orderColumn = null;
        boolean desc = false;
        Object orderBy = body.get("orderBy");
        if (orderBy instanceof Map) {
            Map<?, ?> order = (Map<?, ?>) orderBy;
            ColumnInfo column = columnIndex.get(asString(order.get("column")).toLowerCase(Locale.ROOT));
            if (column != null) {
                orderColumn = column.getName();
                desc = Boolean.TRUE.equals(order.get("desc"));
            }
        }
        if (orderColumn != null) {
            sql.append(" ORDER BY ").append(dialect.quoteAlways(orderColumn)).append(desc ? " DESC" : " ASC");
        } else if (!primaryKeys.isEmpty()) {
            List<String> orderParts = new ArrayList<>(primaryKeys.size());
            for (String key : primaryKeys) {
                orderParts.add(dialect.quoteAlways(key));
            }
            sql.append(" ORDER BY ").append(String.join(", ", orderParts));
        }

        Map<String, Object> result;
        try {
            result = executor.execute(conn, sql.toString(), parameters, page, pageSize);
        } catch (Exception e) {
            throw new IllegalStateException("查询表数据失败: " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connectionId", conn.getId());
        out.put("dbType", conn.getDbType());
        out.put("table", info.getName());
        out.put("page", page);
        out.put("pageSize", pageSize);
        out.put("columns", columns(info));
        out.put("rows", result.get("rows"));
        out.put("total", result.get("total"));
        out.put("durationMs", result.get("durationMs"));
        out.put("sql", sql.toString());
        out.put("primaryKeys", primaryKeys);
        out.put("unstable", primaryKeys.isEmpty());
        out.put("editable", !primaryKeys.isEmpty());
        return out;
    }

    // ==================== CSV 导出 ====================

    /** 按当前筛选/排序导出 CSV（UTF-8 BOM，Excel 直接打开不乱码），最多 {@value #MAX_EXPORT_ROWS} 行。 */
    public String exportCsv(Map<String, Object> body) {
        DatabaseConnection conn = requireConnection(asLong(body.get("connectionId")));
        TableInfo info = requireTable(conn, asString(body.get("table")));
        Dialect dialect = dialects.get(conn.getDbType());
        Map<String, ColumnInfo> columnIndex = columnIndex(info);
        List<String> primaryKeys = DbDdlBuilder.primaryColumns(info);

        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(dialect.quoteAlways(info.getName()));
        List<Object> parameters = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        appendFilters(conditions, parameters, body.get("filters"), columnIndex, dialect);
        appendKeyword(conditions, parameters, asString(body.get("keyword")), info, dialect);
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        if (!primaryKeys.isEmpty()) {
            List<String> parts = new ArrayList<>(primaryKeys.size());
            for (String key : primaryKeys) {
                parts.add(dialect.quoteAlways(key));
            }
            sql.append(" ORDER BY ").append(String.join(", ", parts));
        }

        Map<String, Object> result;
        try {
            result = executor.executeForExport(conn, sql.toString(), parameters, MAX_EXPORT_ROWS);
        } catch (Exception e) {
            throw new IllegalStateException("导出失败: " + e.getMessage(), e);
        }
        List<String> columnNames = new ArrayList<>();
        for (Object column : (List<?>) result.get("columns")) {
            columnNames.add(String.valueOf(column));
        }
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append(csvLine(columnNames));
        for (Object row : (List<?>) result.get("rows")) {
            Map<?, ?> map = (Map<?, ?>) row;
            List<String> values = new ArrayList<>(columnNames.size());
            for (String column : columnNames) {
                values.add(map.get(column) == null ? "" : String.valueOf(map.get(column)));
            }
            csv.append(csvLine(values));
        }
        return csv.toString();
    }

    // ==================== 内部工具 ====================

    private DatabaseConnection requireConnection(Long connectionId) {
        if (connectionId == null) {
            throw new IllegalArgumentException("connectionId 不能为空");
        }
        return connections.get(connectionId);
    }

    /**
     * 表必须真实存在（以结构快照为准），同时拿到最新结构。行级写入 / 结构编辑复用同一套白名单。
     *
     * <p><b>读路径</b>：走 {@link SchemaService#table} 的单表缓存（默认永不过期，靠手动刷新）。
     */
    public TableInfo requireTable(DatabaseConnection conn, String table) {
        return requireTable(conn, table, false);
    }

    /**
     * 同上，但可强制重查。
     *
     * <p><b>写路径必须传 {@code force=true}</b>：这层白名单决定「哪些列能写 + 按什么类型编码」，
     * 永久缓存下外部新增的字段会被判成未知列，写入会一直失败且用户看不出原因。
     * 写操作频率极低（用户点一次提交），多跑一次字典查询完全值得。
     */
    public TableInfo requireTable(DatabaseConnection conn, String table, boolean force) {
        if (table == null || table.trim().isEmpty()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        String name = table.trim();
        if (SchemaService.isSystemTable(name)) {
            throw new IllegalArgumentException("不允许浏览系统表: " + name);
        }
        return schemas.table(conn.getId(), name, force);
    }

    /** 表级刷新：清这张表的结构缓存（下次打开 / 翻页重新抓），清单不受影响。 */
    public void evictTable(Long connectionId, String table) {
        schemas.evictTable(connectionId, table);
    }

    /** 只清表清单缓存：新加/删表后要重拉清单，但不必重拉已缓存的结构。 */
    public void evictTableList(Long connectionId) {
        if (connectionId != null) {
            listCache.evict(String.valueOf(connectionId));
        }
    }

    /** 库级刷新：清该连接的表清单 + 全部单表结构 + 联想缓存（加了字段，编辑器联想也该立刻能看到）。 */
    public void evictSchema(Long connectionId) {
        evictTableList(connectionId);
        schemas.evict(connectionId);
    }

    /** 列结构 → 前端可消费的 Map（线上表浏览复用同一份序列化，保证两套链路字段一致）。 */
    public List<Map<String, Object>> columns(TableInfo info) {
        List<Map<String, Object>> out = new ArrayList<>(info.getColumns().size());
        for (ColumnInfo column : info.getColumns()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", column.getName());
            item.put("type", column.getType());
            item.put("nullable", column.getNullable());
            item.put("primaryKey", Boolean.TRUE.equals(column.getPrimaryKey()));
            item.put("autoIncrement", Boolean.TRUE.equals(column.getAutoIncrement()));
            item.put("defaultValue", column.getDefaultValue());
            item.put("comment", column.getComment());
            out.add(item);
        }
        return out;
    }

    /** 索引结构 → 前端可消费的 Map。 */
    public List<Map<String, Object>> indexes(TableInfo info) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IndexInfo index : info.getIndexes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", index.getName());
            item.put("columns", index.getColumns());
            item.put("unique", index.isUnique());
            item.put("primary", index.isPrimary());
            item.put("type", index.getType());
            out.add(item);
        }
        return out;
    }

    private Map<String, ColumnInfo> columnIndex(TableInfo info) {
        Map<String, ColumnInfo> index = new LinkedHashMap<>();
        for (ColumnInfo column : info.getColumns()) {
            index.put(column.getName().toLowerCase(Locale.ROOT), column);
        }
        return index;
    }

    /** filters: [{column, op, value}]，op ∈ eq/ne/like/gt/ge/lt/le/isnull/notnull。 */
    private void appendFilters(List<String> conditions, List<Object> parameters, Object filters,
                               Map<String, ColumnInfo> columnIndex, Dialect dialect) {
        if (!(filters instanceof List)) {
            return;
        }
        for (Object raw : (List<?>) filters) {
            if (!(raw instanceof Map)) {
                continue;
            }
            Map<?, ?> filter = (Map<?, ?>) raw;
            String name = asString(filter.get("column"));
            ColumnInfo column = columnIndex.get(name == null ? "" : name.toLowerCase(Locale.ROOT));
            if (column == null) {
                continue;
            }
            String op = asString(filter.get("op"));
            op = op == null || op.trim().isEmpty() ? "eq" : op.trim().toLowerCase(Locale.ROOT);
            String q = dialect.quoteAlways(column.getName());
            Object value = filter.get("value");
            switch (op) {
                case "isnull":
                    conditions.add(q + " IS NULL");
                    break;
                case "notnull":
                    conditions.add(q + " IS NOT NULL");
                    break;
                case "like":
                    conditions.add(q + " LIKE ?");
                    parameters.add("%" + (value == null ? "" : String.valueOf(value)) + "%");
                    break;
                case "ne":
                    conditions.add(q + " <> ?");
                    parameters.add(value);
                    break;
                case "gt":
                    conditions.add(q + " > ?");
                    parameters.add(value);
                    break;
                case "ge":
                    conditions.add(q + " >= ?");
                    parameters.add(value);
                    break;
                case "lt":
                    conditions.add(q + " < ?");
                    parameters.add(value);
                    break;
                case "le":
                    conditions.add(q + " <= ?");
                    parameters.add(value);
                    break;
                default:
                    conditions.add(q + " = ?");
                    parameters.add(value);
            }
        }
    }

    /** 顶部快捷搜索：命中任意文本列的 contains（最多 10 列，避免 OR 过长）。 */
    private void appendKeyword(List<String> conditions, List<Object> parameters, String keyword,
                               TableInfo info, Dialect dialect) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return;
        }
        List<String> ors = new ArrayList<>();
        int used = 0;
        for (ColumnInfo column : info.getColumns()) {
            if (used >= MAX_KEYWORD_COLUMNS) {
                break;
            }
            String type = column.getType() == null ? "" : column.getType().toLowerCase(Locale.ROOT);
            boolean textual = type.contains("char") || type.contains("text") || type.contains("clob") || type.contains("enum");
            if (!textual) {
                continue;
            }
            ors.add(dialect.quoteAlways(column.getName()) + " LIKE ?");
            parameters.add("%" + keyword.trim() + "%");
            used++;
        }
        if (ors.isEmpty()) {
            return;
        }
        conditions.add("(" + String.join(" OR ", ors) + ")");
    }

    /** MySQL：SHOW CREATE TABLE 取第 2 列（Create Table）原文。 */
    private String showCreateTable(DatabaseConnection conn, Dialect dialect, String table) throws Exception {
        Map<String, Object> raw = executor.executeRaw(conn, "SHOW CREATE TABLE " + dialect.quoteAlways(table),
                Collections.emptyList());
        for (Map<?, ?> row : rowMaps(raw)) {
            for (Object value : row.values()) {
                if (value instanceof String && ((String) value).toUpperCase(Locale.ROOT).contains("CREATE TABLE")) {
                    return (String) value;
                }
            }
        }
        return null;
    }

    private String commentOf(Map<?, ?> row) {
        String comment = SchemaService.str(row, "comments");
        if (comment == null) {
            comment = SchemaService.str(row, "comment");
        }
        if (comment == null) {
            comment = SchemaService.str(row, "table_comment");
        }
        return comment;
    }

    @SuppressWarnings("unchecked")
    private List<Map<?, ?>> rowMaps(Map<String, Object> raw) {
        if (raw == null || !(raw.get("rows") instanceof List)) {
            return Collections.emptyList();
        }
        return (List<Map<?, ?>>) raw.get("rows");
    }

    private String csvLine(List<String> values) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            String value = values.get(i) == null ? "" : values.get(i);
            boolean quote = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
            if (quote) {
                line.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                line.append(value);
            }
        }
        return line.append("\r\n").toString();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 可空数字解析：非法值返回 null，由调用方决定默认值。 */
    private Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long asLong(Object value, long fallback) {
        Long parsed = nullableLong(value);
        return parsed == null ? fallback : parsed;
    }

    private Long asLong(Object value) {
        return nullableLong(value);
    }
}
