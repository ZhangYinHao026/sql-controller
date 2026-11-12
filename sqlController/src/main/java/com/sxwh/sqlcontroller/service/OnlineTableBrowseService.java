package com.sxwh.sqlcontroller.service;

import com.sxwh.sqlcontroller.config.AutocompleteProperties;
import com.sxwh.sqlcontroller.config.OnlineProperties;
import com.sxwh.sqlcontroller.config.SqlControllerProperties;
import com.sxwh.sqlcontroller.dialect.Dialect;
import com.sxwh.sqlcontroller.dialect.DialectRegistry;
import com.sxwh.sqlcontroller.model.ColumnInfo;
import com.sxwh.sqlcontroller.model.IndexInfo;
import com.sxwh.sqlcontroller.model.OnlineConnection;
import com.sxwh.sqlcontroller.model.SchemaSnapshot;
import com.sxwh.sqlcontroller.model.TableInfo;
import com.sxwh.sqlcontroller.repository.HistoryRepository;
import com.sxwh.sqlcontroller.util.DbValueCodec;
import com.sxwh.sqlcontroller.util.OnlineSqlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线上（生产）表浏览：把「点表看数据 / 看结构」做成与本地一致的体验。
 *
 * <p>链路与本地不同，差异都在这里消化：
 * <ul>
 *   <li>结构：{@link OnlineSchemaService} 经小助手读 {@code information_schema}（单表过滤，不吃整库缓存）；</li>
 *   <li>数据：{@link OnlineQueryService} 只支持「内联字面量」的单条 SELECT，所以值一律走
 *       {@link DbValueCodec#literal} 转义，绝不拼原文；</li>
 *   <li>写入：{@link OnlineWriteService}（SM4 + updateSql 单语句），受 {@code write.online-enabled} 控制。</li>
 * </ul>
 *
 * <p>线上全部是 MySQL，所以 DDL/引号统一按 MySQL 处理。
 */
@Service
public class OnlineTableBrowseService {

    private static final Logger log = LoggerFactory.getLogger(OnlineTableBrowseService.class);

    /** 线上导出行数上限：跨省每次请求几十到几百毫秒，翻太多页没意义。 */
    private static final int MAX_EXPORT_ROWS = 5000;
    /** 线上导出单页大小：部分区域小助手限制 pagesize ≤ 300，取 300 保证跨区域可用。 */
    private static final int EXPORT_PAGE_SIZE = 300;
    /** 表清单翻页保护：单次清单最多翻这么多页（300 × 100 = 3 万张表）。 */
    private static final int MAX_LIST_PAGES = 100;

    private final OnlineConnectionService connections;
    private final OnlineSchemaService schemas;
    private final OnlineQueryService query;
    private final OnlineWriteService writer;
    private final TableBrowseService browse;
    private final DialectRegistry dialects;
    private final SqlControllerProperties properties;
    private final OnlineProperties onlineProperties;
    private final HistoryRepository history;
    private final AutocompleteProperties props;

    /** 表清单缓存：连接 id → (全量清单, 过期时间)，TTL 与单表结构一致（默认永不过期）。 */
    private final Map<Long, TableListEntry> listCache = new ConcurrentHashMap<>();

    public OnlineTableBrowseService(OnlineConnectionService connections, OnlineSchemaService schemas,
                                    OnlineQueryService query, OnlineWriteService writer, TableBrowseService browse,
                                    DialectRegistry dialects, SqlControllerProperties properties,
                                    OnlineProperties onlineProperties, HistoryRepository history,
                                    AutocompleteProperties props) {
        this.connections = connections;
        this.schemas = schemas;
        this.query = query;
        this.writer = writer;
        this.browse = browse;
        this.dialects = dialects;
        this.properties = properties;
        this.onlineProperties = onlineProperties;
        this.history = history;
        this.props = props;
    }

    // ==================== 表清单 ====================

    /**
     * 轻量表清单。线上区域混着 MySQL / 达梦 / 神通三种库：
     * MySQL 走 information_schema 翻页拿全（一次往返几百毫秒）；其余方言退回结构快照（首次慢、之后 24h 缓存）。
     *
     * <p>清单按连接整份缓存（TTL 与单表结构一致，默认永不过期），keyword 只过滤缓存结果。
     */
    public List<Map<String, Object>> tables(Long connectionId, String keyword) {
        OnlineConnection conn = requireConnection(connectionId);
        List<Map<String, Object>> all = cachedTableList(connectionId, conn);
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (kw.isEmpty()) {
            return all;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : all) {
            if (match(String.valueOf(item.get("name")), kw)) {
                out.add(item);
            }
        }
        return out;
    }

    /** 取整份清单：命中缓存直接返回，未命中才真查并回填。 */
    private List<Map<String, Object>> cachedTableList(Long connectionId, OnlineConnection conn) {
        long ttl = props.getTableCacheSeconds();
        if (ttl != 0) {
            TableListEntry entry = listCache.get(connectionId);
            if (entry != null && (entry.expireAt < 0 || System.currentTimeMillis() <= entry.expireAt)) {
                return entry.data;
            }
        }
        List<Map<String, Object>> list = Collections.unmodifiableList(loadAllTables(conn));
        if (ttl != 0) {
            listCache.put(connectionId, new TableListEntry(list,
                    ttl < 0 ? -1L : System.currentTimeMillis() + ttl * 1000L));
        }
        return list;
    }

    /** 真查整份清单：MySQL 翻页取全（不翻页会把 300 张表以后的清单静默截断），其余方言走整库快照。 */
    private List<Map<String, Object>> loadAllTables(OnlineConnection conn) {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean light = isMysql(conn);
        if (light) {
            try {
                String sql = "SELECT table_name AS tableName, table_comment AS tableComment FROM information_schema.tables "
                        + "WHERE table_schema = database() AND table_type = 'BASE TABLE' ORDER BY table_name";
                int page = 1;
                int pages = 1;
                do {
                    OnlineQueryService.Rows rows = query.selectRows(conn, sql, page, EXPORT_PAGE_SIZE);
                    for (Map<String, Object> row : rows.rows) {
                        String name = text(row.get("tableName"));
                        if (name == null) {
                            name = text(row.get("table_name"));
                        }
                        if (name == null) {
                            continue;
                        }
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", name);
                        item.put("comment", text(row.get("tableComment")));
                        out.add(item);
                    }
                    pages = Math.min(Math.max(1, rows.pageCount), MAX_LIST_PAGES);
                    page++;
                } while (page <= pages);
            } catch (Exception e) {
                log.warn("[online-browse] information_schema 表清单失败，回退结构快照 area={} {}: {}",
                        conn.getAreaCode(), conn.getRegionName(), e.getMessage());
                out.clear();
                light = false;
            }
        }
        if (!light) {
            SchemaSnapshot snapshot = schemas.snapshot(conn.getId(), false, null);
            if (!snapshot.isOk()) {
                throw new IllegalStateException("读取线上表清单失败: " + snapshot.getError());
            }
            for (TableInfo table : snapshot.getTables()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", table.getName());
                item.put("comment", table.getComment());
                out.add(item);
            }
        }
        return out;
    }

    private boolean match(String name, String keyword) {
        return keyword == null || keyword.isEmpty() || name.toLowerCase(Locale.ROOT).contains(keyword);
    }

    // ==================== 单表结构 + DDL ====================

    public Map<String, Object> table(Long connectionId, String tableName, boolean refresh) {
        OnlineConnection conn = requireConnection(connectionId);
        TableInfo info = requireTable(conn, tableName, refresh);
        Dialect dialect = dialectOf(conn);
        boolean mysql = isMysql(conn);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connectionId", connectionId);
        out.put("dbType", dbTypeOf(conn));
        out.put("databaseName", conn.getRegionName());
        out.put("regionName", conn.getRegionName());
        out.put("areaCode", conn.getAreaCode());
        out.put("name", info.getName());
        out.put("comment", info.getComment());
        out.put("columns", browse.columns(info));
        out.put("indexes", browse.indexes(info));
        out.put("primaryKeys", DbDdlBuilder.primaryColumns(info));
        out.put("ddl", DbDdlBuilder.build(info, dialect, mysql));
        out.put("ddlSource", "generated");
        out.put("fetchedAt", System.currentTimeMillis());
        out.put("structureCachedAt", schemas.tableCacheCapturedAt(connectionId, info.getName()));
        return out;
    }

    // ==================== 表数据分页 ====================

    public Map<String, Object> rows(Map<String, Object> body) {
        OnlineConnection conn = requireConnection(asLong(body.get("connectionId")));
        TableInfo info = requireTable(conn, asString(body.get("table")));
        Dialect dialect = dialectOf(conn);

        int page = (int) Math.max(1, asLong(body.get("page"), 1));
        int pageSize = (int) Math.min(Math.max(1, asLong(body.get("pageSize"), 50)), 300);

        List<String> primaryKeys = DbDdlBuilder.primaryColumns(info);
        Map<String, ColumnInfo> columns = RowSqlBuilder.columnIndex(info);

        List<String> conditions = new ArrayList<>();
        String keyword = asString(body.get("keyword"));
        appendKeyword(conditions, keyword, info, dialect);

        String from = "FROM " + dialect.quoteAlways(info.getName())
                + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions));

        String orderColumn = null;
        boolean desc = false;
        Object orderBy = body.get("orderBy");
        if (orderBy instanceof Map) {
            Map<?, ?> order = (Map<?, ?>) orderBy;
            ColumnInfo column = columns.get(String.valueOf(order.get("column")).toLowerCase(Locale.ROOT));
            if (column != null) {
                orderColumn = column.getName();
                desc = Boolean.TRUE.equals(order.get("desc"));
            }
        }
        StringBuilder sql = new StringBuilder("SELECT * ").append(from).append(" ORDER BY ");
        if (orderColumn != null) {
            sql.append(dialect.quoteAlways(orderColumn)).append(desc ? " DESC" : " ASC");
        } else if (!primaryKeys.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (String key : primaryKeys) {
                parts.add(dialect.quoteAlways(key));
            }
            sql.append(String.join(", ", parts));
        } else {
            sql.append("1");
        }

        long started = System.currentTimeMillis();
        OnlineQueryService.Rows pageRows = query.selectRows(conn, sql.toString(), page, pageSize);
        long total = countRows(conn, from);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connectionId", conn.getId());
        out.put("dbType", dbTypeOf(conn));
        out.put("table", info.getName());
        out.put("page", page);
        out.put("pageSize", pageSize);
        out.put("columns", browse.columns(info));
        out.put("rows", pageRows.rows);
        out.put("total", total);
        out.put("durationMs", System.currentTimeMillis() - started);
        out.put("sql", sql.toString());
        out.put("primaryKeys", primaryKeys);
        out.put("unstable", primaryKeys.isEmpty());
        out.put("editable", !primaryKeys.isEmpty() && properties.getWrite().isOnlineEnabled());
        return out;
    }

    /** 线上导出 CSV：按 500/页翻，最多 {@value #MAX_EXPORT_ROWS} 行。 */
    public String exportCsv(Map<String, Object> body) {
        OnlineConnection conn = requireConnection(asLong(body.get("connectionId")));
        TableInfo info = requireTable(conn, asString(body.get("table")));
        Dialect dialect = dialectOf(conn);
        List<String> conditions = new ArrayList<>();
        appendKeyword(conditions, asString(body.get("keyword")), info, dialect);
        String from = "FROM " + dialect.quoteAlways(info.getName())
                + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions));
        List<String> primaryKeys = DbDdlBuilder.primaryColumns(info);
        StringBuilder order = new StringBuilder();
        if (!primaryKeys.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (String key : primaryKeys) {
                parts.add(dialect.quoteAlways(key));
            }
            order.append(" ORDER BY ").append(String.join(", ", parts));
        }

        String header = null;
        StringBuilder csv = new StringBuilder("\uFEFF");
        int fetched = 0;
        int page = 1;
        while (fetched < MAX_EXPORT_ROWS) {
            OnlineQueryService.Rows rows = query.selectRows(conn,
                    "SELECT * " + from + order, page, EXPORT_PAGE_SIZE);
            if (header == null) {
                header = String.join(",", rows.columns);
                csv.append(csvLine(rows.columns));
            }
            if (rows.rows.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : rows.rows) {
                List<String> values = new ArrayList<>(rows.columns.size());
                for (String column : rows.columns) {
                    Object value = row.get(column);
                    values.add(value == null ? "" : String.valueOf(value));
                }
                csv.append(csvLine(values));
                fetched++;
                if (fetched >= MAX_EXPORT_ROWS) {
                    break;
                }
            }
            if (rows.rows.size() < EXPORT_PAGE_SIZE) {
                break;
            }
            page++;
        }
        if (header == null) {
            throw new IllegalStateException("导出失败：小助手没有返回任何数据");
        }
        return csv.toString();
    }

    // ==================== 行级写入 ====================

    public Map<String, Object> update(Map<String, Object> body, String clientIp) {
        requireWrite();
        OnlineConnection conn = requireConnection(asLong(body.get("connectionId")));
        TableInfo info = requireTable(conn, asString(body.get("table")), true);
        RowSqlBuilder.Mutation mutation = RowSqlBuilder.update(dialectOf(conn), info,
                mapOf(body.get("pk")), mapOf(body.get("changes")), true);
        return run(conn, info, mutation, body, clientIp);
    }

    public Map<String, Object> insert(Map<String, Object> body, String clientIp) {
        requireWrite();
        OnlineConnection conn = requireConnection(asLong(body.get("connectionId")));
        TableInfo info = requireTable(conn, asString(body.get("table")), true);
        RowSqlBuilder.Mutation mutation = RowSqlBuilder.insert(dialectOf(conn), info,
                mapOf(body.get("values")), true);
        return run(conn, info, mutation, body, clientIp);
    }

    public Map<String, Object> delete(Map<String, Object> body, String clientIp) {
        requireWrite();
        OnlineConnection conn = requireConnection(asLong(body.get("connectionId")));
        TableInfo info = requireTable(conn, asString(body.get("table")), true);
        RowSqlBuilder.Mutation mutation = RowSqlBuilder.delete(dialectOf(conn), info,
                mapOf(body.get("pk")), true);
        return run(conn, info, mutation, body, clientIp);
    }

    /** 线上结构编辑的 DDL 预览：按连接自己的方言生成（线上区域混着 MySQL/达梦/神通）。 */
    public Map<String, Object> previewStructure(Map<String, Object> body) {
        requireWrite();
        OnlineConnection conn = requireConnection(asLong(body.get("connectionId")));
        TableInfo info = requireTable(conn, asString(body.get("table")), true);
        DbAlterBuilder.Plan plan = DbAlterBuilder.build(info, dialectOf(conn), isMysql(conn), ops(body));
        Map<String, Object> out = DbAlterBuilder.describe(plan);
        if (plan.statements.isEmpty()) {
            out.put("warnings", Collections.singletonList("没有检测到结构改动"));
        }
        out.put("table", info.getName());
        return out;
    }

    /** 线上执行 DDL（结构编辑「同步到线上」用）：逐条走 updateSql 通道。 */
    public Map<String, Object> executeDdl(Map<String, Object> body, String clientIp) {
        requireWrite();
        OnlineConnection conn = requireConnection(asLong(body.get("connectionId")));
        List<String> statements = strings(body.get("statements"));
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("没有需要执行的 DDL");
        }
        String requestId = UUID.randomUUID().toString();
        long started = System.currentTimeMillis();
        List<Map<String, Object>> executed = new ArrayList<>();
        int failedIndex = -1;
        String error = null;
        for (int i = 0; i < statements.size(); i++) {
            String sql = OnlineSqlGuard.prepareWrite(statements.get(i), onlineProperties.getWrite().isAllowDelete());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sql", sql);
            try {
                Map<String, Object> result = writer.write(conn, sql);
                boolean ok = "SUCCESS".equalsIgnoreCase(String.valueOf(result.get("status")));
                item.put("ok", ok);
                item.put("affectedRows", result.get("affectedRows"));
                if (!ok) {
                    item.put("error", result.get("errorMessage"));
                    failedIndex = i;
                    error = String.valueOf(result.get("errorMessage"));
                    executed.add(item);
                    break;
                }
            } catch (Exception e) {
                item.put("ok", false);
                item.put("error", e.getMessage());
                failedIndex = i;
                error = e.getMessage();
                executed.add(item);
                break;
            }
            executed.add(item);
        }
        long duration = System.currentTimeMillis() - started;
        history.save(requestId, null, null, null, "online", conn.getRegionName() + "（线上）",
                String.join(";\n", statements), "DDL", "TABLE_STRUCT", error == null ? "SUCCESS" : "FAILED",
                executed.size(), clientIp, duration, error);
        // 结构已变：只清涉及的表（body 里带 table 时），否则整库清——线上小助手重拉代价高，别误伤其他表
        String changedTable = asString(body.get("table"));
        if (changedTable != null && !changedTable.trim().isEmpty()) {
            schemas.evictTable(conn.getId(), changedTable);
        } else {
            schemas.evict(conn.getId());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", error == null);
        out.put("executed", executed.size());
        out.put("total", statements.size());
        out.put("failedIndex", failedIndex);
        out.put("durationMs", duration);
        out.put("statements", executed);
        out.put("message", error == null ? "线上结构变更已执行" : "第 " + (failedIndex + 1) + " 条失败：" + error);
        return out;
    }

    // ==================== 内部 ====================

    private Map<String, Object> run(OnlineConnection conn, TableInfo info, RowSqlBuilder.Mutation mutation,
                                    Map<String, Object> body, String clientIp) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sql", mutation.sql);
        out.put("kind", mutation.kind);
        out.put("table", info.getName());
        out.put("online", true);
        if (truthy(body.get("dryRun"))) {
            out.put("dryRun", true);
            out.put("affectedRows", 0);
            out.put("durationMs", 0);
            return out;
        }

        String requestId = UUID.randomUUID().toString();
        long started = System.currentTimeMillis();
        long affected = 0;
        String status = "SUCCESS";
        String error = null;
        try {
            Map<String, Object> result = writer.write(conn, OnlineSqlGuard.prepareWrite(mutation.sql,
                    onlineProperties.getWrite().isAllowDelete()));
            affected = asLong(result.get("affectedRows"), 0);
            if (!"SUCCESS".equalsIgnoreCase(String.valueOf(result.get("status")))) {
                status = "FAILED";
                error = String.valueOf(result.getOrDefault("errorMessage", "线上写入失败"));
            } else if (affected <= 0) {
                status = "EMPTY";
            }
        } catch (Exception e) {
            status = "FAILED";
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[online-browse] {} 失败 area={} table={}: {}", mutation.kind,
                    conn.getAreaCode(), info.getName(), error);
        }
        long duration = System.currentTimeMillis() - started;
        history.save(requestId, null, null, null, "online", conn.getRegionName() + "（线上）",
                mutation.sql, mutation.kind, "TABLE_GRID", status, affected, clientIp, duration, error);

        out.put("durationMs", duration);
        out.put("affectedRows", affected);
        if ("FAILED".equals(status)) {
            out.put("ok", false);
            out.put("message", "生产环境执行失败：" + error);
        } else if (affected <= 0) {
            out.put("ok", false);
            out.put("message", "没有行被改动：记录可能已被他人修改/删除，或新值与旧值相同");
        } else {
            out.put("message", "生产环境 " + mutation.kind + " 成功，影响 " + affected + " 行");
        }
        return out;
    }

    private long countRows(OnlineConnection conn, String from) {
        try {
            OnlineQueryService.Rows rows = query.selectRows(conn, "SELECT COUNT(*) AS total " + from, 1, 1);
            if (!rows.rows.isEmpty()) {
                Map<String, Object> row = rows.rows.get(0);
                // 达梦/神通会把结果列名折叠成大写（TOTAL），按大小写不敏感取值
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if ("total".equalsIgnoreCase(entry.getKey())) {
                        return asLong(entry.getValue(), 0);
                    }
                }
                // 兜底：只有一个值时直接当总数
                if (row.size() == 1) {
                    return asLong(row.values().iterator().next(), 0);
                }
            }
        } catch (Exception e) {
            log.warn("[online-browse] COUNT 失败 area={}: {}", conn.getAreaCode(), e.getMessage());
        }
        return 0;
    }

    /**
     * 线上表必须真实存在：读路径走单表结构缓存（命中直接返回，未命中只查这一张表）。
     *
     * <p>写路径用 {@link #requireTable(OnlineConnection, String, boolean)} 并传 {@code true} 强制重查——
     * 这层白名单决定「哪些列能写 + 按什么类型转义」，缓存过期会导致外部新增字段永远写不进去。
     */
    private TableInfo requireTable(OnlineConnection conn, String table) {
        return requireTable(conn, table, false);
    }

    private TableInfo requireTable(OnlineConnection conn, String table, boolean force) {
        String name = table == null ? "" : table.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        return schemas.table(conn.getId(), name, force);
    }

    /** 表级刷新：清这张表的线上结构缓存，清单不受影响。 */
    public void evictTable(Long connectionId, String table) {
        schemas.evictTable(connectionId, table);
    }

    /** 只清表清单缓存：线上建/删表后要重拉清单，但不必重拉已缓存的结构。 */
    public void evictTableList(Long connectionId) {
        if (connectionId != null) {
            listCache.remove(connectionId);
        }
    }

    /** 库级刷新：清该线上区域的表清单 + 全部单表结构缓存 + 整库快照。 */
    public void evictSchema(Long connectionId) {
        evictTableList(connectionId);
        schemas.evict(connectionId);
    }

    private Dialect dialectOf(OnlineConnection conn) {
        return dialects.get(dbTypeOf(conn));
    }

    private String dbTypeOf(OnlineConnection conn) {
        return conn.getDbType() == null || conn.getDbType().trim().isEmpty() ? "mysql" : conn.getDbType();
    }

    private boolean isMysql(OnlineConnection conn) {
        return DbDdlBuilder.isMysql(dbTypeOf(conn));
    }

    private boolean isMysqlDialect(Dialect dialect) {
        return dialect != null && "mysql".equalsIgnoreCase(dialect.name());
    }

    private void appendKeyword(List<String> conditions, String keyword, TableInfo info, Dialect dialect) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return;
        }
        boolean mysqlEscapes = isMysqlDialect(dialect);
        List<String> ors = new ArrayList<>();
        int used = 0;
        for (ColumnInfo column : info.getColumns()) {
            if (used >= 10) {
                break;
            }
            String type = column.getType() == null ? "" : column.getType().toLowerCase(Locale.ROOT);
            if (!(type.contains("char") || type.contains("text") || type.contains("enum"))) {
                continue;
            }
            ors.add(dialect.quoteAlways(column.getName()) + " LIKE " + "'%"
                    + DbValueCodec.escape(keyword.trim(), mysqlEscapes) + "%'");
            used++;
        }
        if (!ors.isEmpty()) {
            conditions.add("(" + String.join(" OR ", ors) + ")");
        }
    }

    private void requireWrite() {
        if (!properties.getWrite().isOnlineEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "线上写入口未开启：请在 application.yml 将 sql-controller.write.online-enabled 置为 true 后重启服务");
        }
    }

    private OnlineConnection requireConnection(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("connectionId 不能为空");
        }
        OnlineConnection conn = connections.get(id);
        if (conn == null) {
            throw new IllegalArgumentException("线上连接不存在: " + id);
        }
        return conn;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> ops(Map<String, Object> body) {
        Object value = body.get("ops");
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return Collections.emptyList();
    }

    private List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item instanceof Map) {
                    Object sql = ((Map<?, ?>) item).get("sql");
                    if (sql != null && !String.valueOf(sql).trim().isEmpty()) {
                        out.add(String.valueOf(sql).trim());
                    }
                } else if (item != null && !String.valueOf(item).trim().isEmpty()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        }
        return out;
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

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long asLong(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return (long) Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Long asLong(Object value) {
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

    /** 供控制器判断：线上能否写（前端决定是否出现编辑入口）。 */
    public boolean onlineWriteEnabled() {
        return properties.getWrite().isOnlineEnabled();
    }

    /** 结构快照里的索引（保留给后续「线上索引编辑」复用）。 */
    public List<IndexInfo> indexesOf(TableInfo table) {
        return table == null ? Collections.emptyList() : table.getIndexes();
    }

    /** 表清单缓存条目：expireAt < 0 表示永不过期（与单表结构缓存同一套语义）。 */
    private static class TableListEntry {
        final List<Map<String, Object>> data;
        final long expireAt;

        TableListEntry(List<Map<String, Object>> data, long expireAt) {
            this.data = data;
            this.expireAt = expireAt;
        }
    }
}
