package com.sxwh.sqlcontroller.service;

import com.sxwh.sqlcontroller.config.AutocompleteProperties;
import com.sxwh.sqlcontroller.dialect.Dialect;
import com.sxwh.sqlcontroller.dialect.DialectRegistry;
import com.sxwh.sqlcontroller.model.ColumnInfo;
import com.sxwh.sqlcontroller.model.DatabaseConnection;
import com.sxwh.sqlcontroller.model.SchemaResponse;
import com.sxwh.sqlcontroller.model.SchemaSnapshot;
import com.sxwh.sqlcontroller.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SQL 编辑器联想数据源服务。
 *
 * <p>两步查询（避免大批量列元数据导致 row 处理出错）：
 * <ol>
 *   <li>先 {@code information_schema.tables WHERE table_schema=?} 拿表名清单（行数=表数，几百行）；</li>
 *   <li>再对每张表单独查 {@code information_schema.columns WHERE table_schema=? AND table_name=?} 拿列。</li>
 * </ol>
 * 前端按需 force reload / 切连接重拉；后端按 connectionId 缓存（TTL）。
 */
@Service
public class SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaService.class);

    private final DatabaseConnectionService connections;
    private final DynamicDataSourceService executor;
    private final DialectRegistry registry;
    private final AutocompleteProperties props;

    /** 简单内存缓存：connectionId → (数据, 过期时间)。 */
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 字段对比快照缓存：connectionId → (快照, 过期时间)，独立于联想缓存（不同 TTL 语义/数据源）。 */
    private final Map<Long, SnapshotEntry> snapshotCache = new ConcurrentHashMap<>();

    /**
     * 抓快照专用线程池：固定大小 8，避免单次 batch 把 10 库全推到同瞬间连 DB。
     * daemon 线程随 JVM 退出；命名 schema-snap-N 便于排障。
     */
    private final ExecutorService snapshotExecutor = Executors.newFixedThreadPool(8, new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(1);
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "schema-snap-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    public SchemaService(DatabaseConnectionService connections, DynamicDataSourceService executor,
                         DialectRegistry registry, AutocompleteProperties props) {
        this.connections = connections;
        this.executor = executor;
        this.registry = registry;
        this.props = props;
    }

    /** 加载联想 schema；任何不可用情况返回 enabled=false（前端降级，不抛错）。force=true 时跳过缓存。 */
    public SchemaResponse loadSchema(boolean force) {
        if (!props.isEnabled()) {
            return SchemaResponse.disabled("联想功能未启用（sql-controller.autocomplete.enabled=false）");
        }
        Long cid = resolveConnectionId();
        if (cid == null) {
            return SchemaResponse.disabled("未配置联想数据源或没有可用连接");
        }
        if (!force) {
            CacheEntry entry = cache.get(cid);
            if (entry != null && !entry.isExpired()) {
                log.info("[SchemaService] cache hit connectionId={} tables={}", cid, entry.data.getTables() == null ? 0 : entry.data.getTables().size());
                return entry.data;
            }
        } else {
            cache.remove(cid);
            log.info("[SchemaService] force reload connectionId={}", cid);
        }
        try {
            DatabaseConnection conn = connections.get(cid);
            Dialect dialect = registry.get(conn.getDbType());
            List<TableInfo> tables = loadTables(conn, dialect);

            SchemaResponse resp = new SchemaResponse();
            resp.setEnabled(true);
            resp.setConnectionId(cid);
            resp.setDatabaseName(conn.getDatabaseName());
            resp.setTables(tables);
            cache.put(cid, new CacheEntry(resp, System.currentTimeMillis() + props.getSchemaCacheSeconds() * 1000));
            log.info("[SchemaService] loaded connectionId={} db={} tables={}", cid, conn.getDatabaseName(), tables.size());
            return resp;
        } catch (Exception e) {
            log.warn("[SchemaService] 读取元数据失败 connectionId={}: {}", cid, e.getMessage());
            return SchemaResponse.disabled("读取元数据失败: " + e.getMessage());
        }
    }

    /**
     * 批量结构快照（字段对比用）：一次抓取多个连接（基准库 + 比对库），单库失败不影响其它库。
     *
     * <p>并发抓取（线程池容量 8）：总耗时由最慢的那个连接决定。
     * 10 库实测由 ~235s（串行）降到 ~50s（并发），近似提速 4~5 倍。
     *
     * @param ids   连接 id 列表
     * @param force true=跳过缓存强制重抓
     */
    public List<SchemaSnapshot> snapshots(List<Long> ids, boolean force) {
        return snapshots(ids, force, null);
    }

    /**
     * 批量结构快照：支持 tableFilter 局部刷新（执行补丁后只刷新涉及的表）。
     * tableFilter 非空时忽略缓存（目标库结构刚被补丁改变，缓存必旧）并真查这些表；
     * 返回只含这些表的结构（表已删除则不在结果中），由前端合并进本地快照。
     */
    public List<SchemaSnapshot> snapshots(List<Long> ids, boolean force, List<String> tableFilter) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        // 过滤 null，保留顺序
        List<Long> nonNullIds = new ArrayList<>(ids.size());
        for (Long id : ids) {
            if (id != null) nonNullIds.add(id);
        }
        if (nonNullIds.isEmpty()) {
            return Collections.emptyList();
        }
        boolean filtered = tableFilter != null && !tableFilter.isEmpty();
        // 并发提交：snapshot() 内部已处理异常转 ok=false，不会向外抛
        List<CompletableFuture<SchemaSnapshot>> futures = new ArrayList<>(nonNullIds.size());
        for (Long id : nonNullIds) {
            futures.add(CompletableFuture.supplyAsync(() -> snapshot(id, force, tableFilter), snapshotExecutor));
        }
        // 顺序收集：保留入参顺序（前端按 id 匹配），用 join 而非 get 避免 checked exception
        List<SchemaSnapshot> out = new ArrayList<>(futures.size());
        for (CompletableFuture<SchemaSnapshot> f : futures) {
            out.add(f.join());
        }
        return out;
    }

    /** 抓取单个连接的结构快照（不依赖联想开关配置）。 */
    public SchemaSnapshot snapshot(Long connectionId, boolean force) {
        return snapshot(connectionId, force, null);
    }

    /**
     * 抓取单个连接的结构快照。
     *
     * @param tableNames 非空 = 局部刷新：仅抓这些表（跳过缓存、不写缓存，防止“只含少数表”
     *                   的半截快照污染整库缓存）；表已被 DROP 时不出现在返回中。
     */
    public SchemaSnapshot snapshot(Long connectionId, boolean force, List<String> tableNames) {
        if (connectionId == null) {
            return fail(null, null, "连接 id 为空");
        }
        boolean filtered = tableNames != null && !tableNames.isEmpty();
        if (!filtered && !force) {
            SnapshotEntry entry = snapshotCache.get(connectionId);
            if (entry != null && !entry.isExpired()) {
                return entry.data;
            }
        }
        try {
            DatabaseConnection conn = connections.get(connectionId);
            if (conn == null) {
                return fail(connectionId, null, "连接不存在或已删除");
            }
            Dialect dialect = registry.get(conn.getDbType());
            SchemaSnapshot snap = new SchemaSnapshot();
            snap.setConnectionId(connectionId);
            snap.setDbType(conn.getDbType());
            snap.setDatabaseName(conn.getDatabaseName());
            snap.setFetchedAt(System.currentTimeMillis());
            snap.setOk(true);
            if (filtered) {
                // 局部刷新：只查目标表（逐表一条 detail 查询），绝不整库拉取
                snap.setTables(loadSnapshotTablesFiltered(conn, dialect, tableNames));
                log.info("[SchemaService] snapshot filtered connectionId={} db={} type={} requestedTables={} returned={}",
                        connectionId, conn.getDatabaseName(), conn.getDbType(), tableNames.size(), snap.getTables().size());
            } else {
                List<TableInfo> tables = loadSnapshotTables(conn, dialect);
                snap.setTables(tables);
                snapshotCache.put(connectionId,
                        new SnapshotEntry(snap, System.currentTimeMillis() + props.getSchemaCacheSeconds() * 1000L));
                log.info("[SchemaService] snapshot loaded connectionId={} db={} type={} tables={}",
                        connectionId, conn.getDatabaseName(), conn.getDbType(), tables.size());
            }
            return snap;
        } catch (Exception e) {
            log.warn("[SchemaService] 结构快照失败 connectionId={}: {}", connectionId, e.getMessage());
            return fail(connectionId, null, "读取结构失败: " + e.getMessage());
        }
    }

    /**
     * 局部刷新专用：对列出的每张表单独查列详情（MySQL 详情行即含主键/自增/注释；
     * DM/神通额外补主键、注释，失败降级）。表查不到列（已 DROP 或名字大小写不匹配）
     * 视为不存在 → 不出现在返回中，前端据此移除本地旧条目。
     */
    private List<TableInfo> loadSnapshotTablesFiltered(DatabaseConnection conn, Dialect dialect,
                                                       List<String> tableNames) throws Exception {
        String schema = dialect.schemaName(conn);
        boolean mysql = "mysql".equalsIgnoreCase(conn.getDbType());
        String detailSql = dialect.schemaColumnDetailQuery();
        String pkSql = mysql ? null : dialect.schemaPkColumnsQuery();
        String commentSql = mysql ? null : dialect.schemaColumnCommentQuery();
        List<TableInfo> out = new ArrayList<>(tableNames.size());
        for (String tableName : tableNames) {
            if (isSystemTable(tableName)) {
                continue;
            }
            try {
                Map<String, Boolean> pkCols = null;
                Map<String, String> comments = null;
                if (!mysql) {
                    try {
                        if (pkSql != null) {
                            pkCols = queryNameSet(executor.executeRaw(conn, pkSql, Arrays.asList(schema, tableName)), "column_name");
                        }
                    } catch (Exception e) {
                        log.warn("[SchemaService] 局部刷新主键增强失败将降级 dbType={} table={}: {}", conn.getDbType(), tableName, e.getMessage());
                    }
                    try {
                        if (commentSql != null) {
                            Map<String, Object> cm = executor.executeRaw(conn, commentSql, Arrays.asList(schema, tableName));
                            comments = queryStringMap(cm, "column_name", new String[]{"comments", "comment"});
                        }
                    } catch (Exception e) {
                        log.warn("[SchemaService] 局部刷新注释增强失败将降级 dbType={} table={}: {}", conn.getDbType(), tableName, e.getMessage());
                    }
                }
                TableInfo info = new TableInfo(tableName);
                for (Map<?, ?> row : rowMaps(executor.executeRaw(conn, detailSql, Arrays.asList(schema, tableName)))) {
                    ColumnInfo col = toColumnInfo(row, mysql, pkCols, comments);
                    if (col != null) {
                        info.getColumns().add(col);
                    }
                }
                if (!info.getColumns().isEmpty()) {
                    out.add(info);
                }
            } catch (Exception e) {
                log.warn("[SchemaService] 局部刷新表 {} 结构失败: {}", tableName, e.getMessage());
            }
        }
        return out;
    }

    private SchemaSnapshot fail(Long connectionId, String dbType, String message) {
        SchemaSnapshot snap = new SchemaSnapshot();
        snap.setConnectionId(connectionId);
        snap.setDbType(dbType);
        snap.setOk(false);
        snap.setError(message);
        return snap;
    }

    /**
     * 快照专用表加载：先拿表清单，再抓列元数据。
     *
     * <p>批量优先（根治逐表 N+1）：方言支持时每个库只发固定 1~3 条字典查询拉全量
     * （MySQL information_schema.columns 一条即含主键/自增/注释/可空；DM/神通 all_tab_columns +
     * 主键 + 注释三条），结果在内存按表名分组。批量查询失败（个别库字典异常）自动回退逐表路径；
     * 主键/注释增强查询失败降级跳过，不影响列详情主流程（与逐表语义一致）。
     */
    private List<TableInfo> loadSnapshotTables(DatabaseConnection conn, Dialect dialect) throws Exception {
        String schema = dialect.schemaName(conn);
        String tablesSql = dialect.schemaTablesQuery();
        Map<String, Object> tableResult = executor.executeRaw(conn, tablesSql, Collections.singletonList(schema));
        List<String> tableNames = readTableNames(tableResult);
        log.info("[SchemaService] snapshot step1 connectionId={} tables={} dbType={}",
                conn.getId(), tableNames.size(), conn.getDbType());

        boolean mysql = "mysql".equalsIgnoreCase(conn.getDbType());
        if (dialect.schemaAllColumnsQuery() != null) {
            try {
                List<TableInfo> batched = loadSnapshotTablesBatch(conn, dialect, schema, tableNames, mysql);
                log.info("[SchemaService] snapshot batched connectionId={} dbType={} tables={}",
                        conn.getId(), conn.getDbType(), batched.size());
                return batched;
            } catch (Exception e) {
                log.warn("[SchemaService] 批量快照失败将回退逐表 connectionId={}: {}", conn.getId(), e.getMessage());
            }
        }
        return loadSnapshotTablesPerTable(conn, dialect, schema, tableNames, mysql);
    }

    /** 批量路径：固定 1~3 条查询拉全库列元数据，Java 内存按表名分组拼装（表顺序=表清单顺序）。 */
    private List<TableInfo> loadSnapshotTablesBatch(DatabaseConnection conn, Dialect dialect, String schema,
                                                    List<String> tableNames, boolean mysql) throws Exception {
        Map<String, List<Map<?, ?>>> colRowsByTable = groupRowsByTable(
                executor.executeRaw(conn, dialect.schemaAllColumnsQuery(), Collections.singletonList(schema)));

        Map<String, Map<String, Boolean>> pkByTable = null;
        Map<String, Map<String, String>> commentByTable = null;
        boolean enrichWarned = false;
        if (!mysql) {
            String pkSql = dialect.schemaAllPkColumnsQuery();
            if (pkSql != null) {
                try {
                    pkByTable = groupNameSetByTable(executor.executeRaw(conn, pkSql, Collections.singletonList(schema)));
                } catch (Exception e) {
                    log.warn("[SchemaService] 批量主键增强失败将降级 dbType={}: {}", conn.getDbType(), e.getMessage());
                    enrichWarned = true;
                }
            }
            String commentSql = dialect.schemaAllColumnCommentsQuery();
            if (commentSql != null) {
                try {
                    commentByTable = groupStringMapByTable(executor.executeRaw(conn, commentSql, Collections.singletonList(schema)));
                } catch (Exception e) {
                    if (!enrichWarned) {
                        log.warn("[SchemaService] 批量注释增强失败将降级 dbType={}: {}", conn.getDbType(), e.getMessage());
                    }
                }
            }
        }

        Map<String, TableInfo> byName = new LinkedHashMap<>();
        int truncated = 0;
        for (String tableName : tableNames) {
            if (byName.size() >= props.getMaxTables()) {
                truncated += tableNames.size() - byName.size();
                break;
            }
            if (isSystemTable(tableName)) {
                continue;
            }
            try {
                TableInfo info = new TableInfo(tableName);
                Map<String, Boolean> pkCols = pkByTable == null ? null : pkByTable.get(tableName);
                Map<String, String> comments = commentByTable == null ? null : commentByTable.get(tableName);
                for (Map<?, ?> row : colRowsByTable.getOrDefault(tableName, Collections.emptyList())) {
                    ColumnInfo col = toColumnInfo(row, mysql, pkCols, comments);
                    if (col != null) {
                        info.getColumns().add(col);
                    }
                }
                byName.put(tableName, info);
            } catch (Exception e) {
                log.warn("[SchemaService] 表 {} 结构快照失败: {}", tableName, e.getMessage());
            }
        }
        if (truncated > 0) {
            log.warn("[SchemaService] snapshot maxTables={} 截断，跳过 {} 张表", props.getMaxTables(), truncated);
        }
        return new ArrayList<>(byName.values());
    }

    /** 逐表路径（批量不可用/失败的回退）：每张表独立查列详情；DM/神通额外逐表查主键、注释，失败降级。 */
    private List<TableInfo> loadSnapshotTablesPerTable(DatabaseConnection conn, Dialect dialect, String schema,
                                                       List<String> tableNames, boolean mysql) throws Exception {
        Map<String, TableInfo> byName = new LinkedHashMap<>();
        String detailSql = dialect.schemaColumnDetailQuery();
        String pkSql = mysql ? null : dialect.schemaPkColumnsQuery();
        String commentSql = mysql ? null : dialect.schemaColumnCommentQuery();
        boolean enrichWarned = false;
        int truncated = 0;

        for (String tableName : tableNames) {
            if (byName.size() >= props.getMaxTables()) {
                truncated += tableNames.size() - byName.size();
                break;
            }
            if (isSystemTable(tableName)) {
                continue;
            }
            try {
                TableInfo info = new TableInfo(tableName);
                Map<String, Boolean> pkCols = null;
                Map<String, String> comments = null;
                if (!mysql) {
                    try {
                        if (pkSql != null) {
                            pkCols = queryNameSet(executor.executeRaw(conn, pkSql, Arrays.asList(schema, tableName)), "column_name");
                        }
                    } catch (Exception e) {
                        if (!enrichWarned) {
                            log.warn("[SchemaService] 主键增强查询失败将降级 dbType={} table={}: {}", conn.getDbType(), tableName, e.getMessage());
                            enrichWarned = true;
                        }
                    }
                    try {
                        if (commentSql != null) {
                            Map<String, Object> cm = executor.executeRaw(conn, commentSql, Arrays.asList(schema, tableName));
                            comments = queryStringMap(cm, "column_name", new String[]{"comments", "comment"});
                        }
                    } catch (Exception e) {
                        if (!enrichWarned) {
                            log.warn("[SchemaService] 注释增强查询失败将降级 dbType={} table={}: {}", conn.getDbType(), tableName, e.getMessage());
                            enrichWarned = true;
                        }
                    }
                }
                for (Map<?, ?> row : rowMaps(executor.executeRaw(conn, detailSql, Arrays.asList(schema, tableName)))) {
                    ColumnInfo col = toColumnInfo(row, mysql, pkCols, comments);
                    if (col != null) {
                        info.getColumns().add(col);
                    }
                }
                byName.put(tableName, info);
            } catch (Exception e) {
                log.warn("[SchemaService] 表 {} 结构快照失败: {}", tableName, e.getMessage());
            }
        }
        if (truncated > 0) {
            log.warn("[SchemaService] snapshot maxTables={} 截断，跳过 {} 张表", props.getMaxTables(), truncated);
        }
        return new ArrayList<>(byName.values());
    }

    /** 字典行 → ColumnInfo（MySQL 语义行 / DM·神通语义行共用；主键、注释来自增强映射或行内字段）。 */
    private ColumnInfo toColumnInfo(Map<?, ?> row, boolean mysql, Map<String, Boolean> pkCols, Map<String, String> comments) {
        String column = str(row, "column_name");
        if (column == null) {
            return null;
        }
        String type = firstNonNull(str(row, "column_type"), str(row, "data_type"));
        ColumnInfo col = new ColumnInfo(column, type);
        if (mysql) {
            col.setNullable(!isNo(firstNonNull(str(row, "is_nullable"), str(row, "nullable"))));
            col.setPrimaryKey("PRI".equalsIgnoreCase(str(row, "column_key")));
            col.setAutoIncrement(containsIgnoreCase(str(row, "extra"), "auto_increment"));
            col.setComment(str(row, "column_comment"));
        } else {
            col.setNullable(nullableOrNull(firstNonNull(str(row, "is_nullable"), str(row, "nullable"))));
            col.setPrimaryKey(pkCols == null ? null : pkCols.containsKey(norm(column)));
            col.setAutoIncrement(null);
            col.setComment(comments == null ? null : comments.get(norm(column)));
        }
        return col;
    }

    /** 执行结果 rows（过滤非 Map 行），保持行序。 */
    @SuppressWarnings("unchecked")
    private List<Map<?, ?>> rowMaps(Map<String, Object> result) {
        List<Map<?, ?>> out = new ArrayList<>();
        Object rowsObj = result.get("rows");
        if (rowsObj instanceof List<?>) {
            for (Object rowObj : (List<?>) rowsObj) {
                if (rowObj instanceof Map) {
                    out.add((Map<?, ?>) rowObj);
                }
            }
        }
        return out;
    }

    /** rows 按 table_name 分组（组内保持行序=列序）。 */
    private Map<String, List<Map<?, ?>>> groupRowsByTable(Map<String, Object> result) {
        Map<String, List<Map<?, ?>>> out = new LinkedHashMap<>();
        for (Map<?, ?> row : rowMaps(result)) {
            String table = str(row, "table_name");
            if (table == null) {
                continue;
            }
            out.computeIfAbsent(table, k -> new ArrayList<>()).add(row);
        }
        return out;
    }

    /** rows 按 table_name 分组 → 表内 column_name 小写规范化集合（主键批量）。 */
    private Map<String, Map<String, Boolean>> groupNameSetByTable(Map<String, Object> result) {
        Map<String, Map<String, Boolean>> out = new LinkedHashMap<>();
        for (Map<?, ?> row : rowMaps(result)) {
            String table = str(row, "table_name");
            String column = str(row, "column_name");
            if (table == null || column == null) {
                continue;
            }
            out.computeIfAbsent(table, k -> new LinkedHashMap<>()).put(norm(column), Boolean.TRUE);
        }
        return out;
    }

    /** rows 按 table_name 分组 → 表内列注释映射（注释批量，comments 或 comment 列皆可）。 */
    private Map<String, Map<String, String>> groupStringMapByTable(Map<String, Object> result) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map<?, ?> row : rowMaps(result)) {
            String table = str(row, "table_name");
            String column = str(row, "column_name");
            if (table == null || column == null) {
                continue;
            }
            String comment = str(row, "comments");
            if (comment == null) {
                comment = str(row, "comment");
            }
            if (comment != null) {
                out.computeIfAbsent(table, k -> new LinkedHashMap<>()).put(norm(column), comment);
            }
        }
        return out;
    }

    /** 执行结果 rows 里取某列形成小写规范化 Set（用于主键列集合）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Boolean> queryNameSet(Map<String, Object> result, String key) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        Object rowsObj = result.get("rows");
        if (!(rowsObj instanceof List<?>)) {
            return map;
        }
        for (Object rowObj : (List<?>) rowsObj) {
            if (!(rowObj instanceof Map)) {
                continue;
            }
            String v = str((Map<?, ?>) rowObj, key);
            if (v != null) {
                map.put(norm(v), Boolean.TRUE);
            }
        }
        return map;
    }

    /** 执行结果 rows 里取 key 列 → value 列的小写规范化映射（用于注释探测）。 */
    @SuppressWarnings("unchecked")
    private Map<String, String> queryStringMap(Map<String, Object> result, String keyCol, String[] valueCols) {
        Map<String, String> map = new LinkedHashMap<>();
        Object rowsObj = result.get("rows");
        if (!(rowsObj instanceof List<?>)) {
            return map;
        }
        for (Object rowObj : (List<?>) rowsObj) {
            if (!(rowObj instanceof Map)) {
                continue;
            }
            Map<?, ?> row = (Map<?, ?>) rowObj;
            String key = str(row, keyCol);
            if (key == null) {
                continue;
            }
            for (String vc : valueCols) {
                String v = str(row, vc);
                if (v != null) {
                    map.put(norm(key), v);
                    break;
                }
            }
        }
        return map;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isNo(String s) {
        return s != null && (s.equalsIgnoreCase("NO") || s.equalsIgnoreCase("N"));
    }

    private static Boolean nullableOrNull(String raw) {
        return raw == null ? null : !isNo(raw);
    }

    private static boolean containsIgnoreCase(String s, String needle) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /** 解析联想数据源连接 id：auto=第一条连接；否则按配置的 connectionId。 */
    private Long resolveConnectionId() {
        String source = props.getSchemaSource() == null ? "auto" : props.getSchemaSource().trim();
        if ("auto".equalsIgnoreCase(source)) {
            List<DatabaseConnection> all = connections.list(null, null);
            return all == null || all.isEmpty() ? null : all.get(0).getId();
        }
        try {
            return Long.parseLong(source);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 两步加载：先 information_schema.tables 拿清单（最多 maxTables 行），
     * 再对每张表 information_schema.columns 拿列（最多 maxColumns 列）。
     * 失败的单表日志 warn 但不影响其它表。
     */
    private List<TableInfo> loadTables(DatabaseConnection conn, Dialect dialect) throws Exception {
        String schema = dialect.schemaName(conn);
        Map<String, TableInfo> byName = new LinkedHashMap<>();

        String tablesSql = dialect.schemaTablesQuery();
        log.info("[SchemaService] step1 tables sql={} schema={}", tablesSql, schema);
        // 用 executeRaw 避开 maxPageSize 对元数据查询的钳制（拿表名清单应一次拉全）
        Map<String, Object> tableResult = executor.executeRaw(conn, tablesSql, Collections.singletonList(schema));
        List<String> tableNames = readTableNames(tableResult);
        log.info("[SchemaService] step1 got {} tables: {}", tableNames.size(),
                tableNames.size() <= 10 ? tableNames : (tableNames.subList(0, 10) + "...(+" + (tableNames.size() - 10) + ")"));

        String colsSql = "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        int colsLimit = Math.max(1, props.getMaxColumns());
        int truncated = 0;
        for (String tableName : tableNames) {
            if (byName.size() >= props.getMaxTables()) {
                truncated += tableNames.size() - byName.size();
                break;
            }
            if (isSystemTable(tableName)) {
                continue;
            }
            try {
                Map<String, Object> colResult = executor.executeRaw(conn, colsSql,
                        Arrays.asList(schema, tableName));
                TableInfo info = new TableInfo(tableName);
                Object rowsObj = colResult.get("rows");
                if (rowsObj instanceof List<?>) {
                    for (Object rowObj : (List<?>) rowsObj) {
                        if (!(rowObj instanceof Map)) continue;
                        Map<?, ?> row = (Map<?, ?>) rowObj;
                        String column = str(row, "column_name");
                        String type = str(row, "data_type");
                        if (column != null) info.getColumns().add(new ColumnInfo(column, type));
                    }
                }
                byName.put(tableName, info);
            } catch (Exception e) {
                log.warn("[SchemaService] 表 {} 列元数据查询失败: {}", tableName, e.getMessage());
            }
        }
        if (truncated > 0) {
            log.warn("[SchemaService] maxTables={} 截断，跳过 {} 张表", props.getMaxTables(), truncated);
        }
        log.info("[SchemaService] step2 done total={} tables", byName.size());
        return new ArrayList<>(byName.values());
    }

    /** 从 information_schema.tables 查询结果里读取 table_name 列（不区分大小写）。 */
    @SuppressWarnings("unchecked")
    private List<String> readTableNames(Map<String, Object> tableResult) {
        List<String> names = new ArrayList<>();
        Object rowsObj = tableResult.get("rows");
        if (!(rowsObj instanceof List<?>)) return names;
        for (Object rowObj : (List<?>) rowsObj) {
            if (!(rowObj instanceof Map)) continue;
            Map<String, Object> row = (Map<String, Object>) rowObj;
            Object name = null;
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if ("table_name".equalsIgnoreCase(e.getKey())) { name = e.getValue(); break; }
            }
            if (name != null) names.add(name.toString());
        }
        return names;
    }

    /** 系统表过滤：information_schema / sys% / mysql% / 含 $ 的临时表。 */
    private boolean isSystemTable(String table) {
        String t = table.toLowerCase(Locale.ROOT);
        return t.startsWith("information_schema")
                || t.startsWith("mysql")
                || t.startsWith("performance_schema")
                || t.contains("$");
    }

    /** 大小写不敏感取值（MySQL 小写列名 / 达梦可能大写列名）。 */
    private String str(Map<?, ?> row, String key) {
        if (row == null) return null;
        for (Map.Entry<?, ?> e : row.entrySet()) {
            if (e.getKey() != null && key.equalsIgnoreCase(e.getKey().toString())) {
                return e.getValue() == null ? null : String.valueOf(e.getValue());
            }
        }
        return null;
    }

    private static class CacheEntry {
        final SchemaResponse data;
        final long expireAt;

        CacheEntry(SchemaResponse data, long expireAt) {
            this.data = data;
            this.expireAt = expireAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    private static class SnapshotEntry {
        final SchemaSnapshot data;
        final long expireAt;

        SnapshotEntry(SchemaSnapshot data, long expireAt) {
            this.data = data;
            this.expireAt = expireAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    /** 应用关闭时优雅释放快照线程池（最长等 5s）。 */
    @javax.annotation.PreDestroy
    public void shutdown() {
        snapshotExecutor.shutdown();
        try {
            if (!snapshotExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                snapshotExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            snapshotExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}