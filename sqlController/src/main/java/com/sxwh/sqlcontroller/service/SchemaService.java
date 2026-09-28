package com.sxwh.sqlcontroller.service;

import com.sxwh.sqlcontroller.config.AutocompleteProperties;
import com.sxwh.sqlcontroller.dialect.Dialect;
import com.sxwh.sqlcontroller.dialect.DialectRegistry;
import com.sxwh.sqlcontroller.model.ColumnInfo;
import com.sxwh.sqlcontroller.model.DatabaseConnection;
import com.sxwh.sqlcontroller.model.IndexInfo;
import com.sxwh.sqlcontroller.model.SchemaResponse;
import com.sxwh.sqlcontroller.model.SchemaSnapshot;
import com.sxwh.sqlcontroller.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
        Map<String, Map<String, Boolean>> identityByTable = loadIdentityByTable(conn, dialect, schema, mysql);
        Map<String, List<IndexInfo>> indexByTable = loadIndexesByTable(conn, dialect, schema);
        Map<String, String> tableCommentByTable = loadTableComments(conn, dialect, schema);
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
                    ColumnInfo col = toColumnInfo(row, mysql, pkCols, comments,
                            identityByTable == null ? null : identityByTable.get(tableName));
                    if (col != null) {
                        info.getColumns().add(col);
                    }
                }
                if (!info.getColumns().isEmpty()) {
                    enrichTableMeta(info, indexByTable, tableCommentByTable);
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
        Map<String, Map<String, Boolean>> identityByTable = loadIdentityByTable(conn, dialect, schema, mysql);
        Map<String, Map<String, String>> commentByTable = null;
        Map<String, List<IndexInfo>> indexByTable = loadIndexesByTable(conn, dialect, schema);
        Map<String, String> tableCommentByTable = loadTableComments(conn, dialect, schema);
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
                Map<String, Boolean> identityCols = identityByTable == null ? null : identityByTable.get(tableName);
                for (Map<?, ?> row : colRowsByTable.getOrDefault(tableName, Collections.emptyList())) {
                    ColumnInfo col = toColumnInfo(row, mysql, pkCols, comments, identityCols);
                    if (col != null) {
                        info.getColumns().add(col);
                    }
                }
                enrichTableMeta(info, indexByTable, tableCommentByTable);
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
        Map<String, Map<String, Boolean>> identityByTable = loadIdentityByTable(conn, dialect, schema, mysql);
        Map<String, List<IndexInfo>> indexByTable = loadIndexesByTable(conn, dialect, schema);
        Map<String, String> tableCommentByTable = loadTableComments(conn, dialect, schema);
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
                    ColumnInfo col = toColumnInfo(row, mysql, pkCols, comments,
                            identityByTable == null ? null : identityByTable.get(tableName));
                    if (col != null) {
                        info.getColumns().add(col);
                    }
                }
                enrichTableMeta(info, indexByTable, tableCommentByTable);
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

    /**
     * 全库自增列映射（表名 → 表内列名规范化集合），供逐表/局部刷新路径共用。
     *
     * <p>自增标识只能整库拉（DM 走系统表 syscolumns，无法按表过滤），一次查询后在内存按表名取子集。
     * MySQL 详情查询自带 extra=auto_increment，无需本条；方言不支持或查询失败返回 null
     * （autoIncrement 保持未探测=null，与旧行为一致，不影响主流程）。
     */
    private Map<String, Map<String, Boolean>> loadIdentityByTable(DatabaseConnection conn, Dialect dialect,
                                                                  String schema, boolean mysql) {
        if (mysql) {
            return null;
        }
        String sql = dialect.schemaAllIdentityColumnsQuery();
        if (sql == null) {
            return null;
        }
        try {
            return groupNameSetByTable(executor.executeRaw(conn, sql, Collections.singletonList(schema)));
        } catch (Exception e) {
            log.warn("[SchemaService] 自增增强查询失败将降级 dbType={}: {}", conn.getDbType(), e.getMessage());
            return null;
        }
    }

    /**
     * 字典行 → ColumnInfo（MySQL 语义行 / DM·神通语义行共用；主键、注释、自增来自增强映射或行内字段）。
     *
     * <p>类型文本：MySQL 的 column_type 自带长度精度；DM/神通字典只给裸类型名，需按
     * char_length / data_precision / data_scale 还原（见 {@link #composeType}），否则
     * 下游生成的 MySQL 风格 DDL 会出现「VARCHAR 无长度」这类非法定义。
     */
    private ColumnInfo toColumnInfo(Map<?, ?> row, boolean mysql, Map<String, Boolean> pkCols,
                                    Map<String, String> comments, Map<String, Boolean> identityCols) {
        String column = str(row, "column_name");
        if (column == null) {
            return null;
        }
        String type = firstNonNull(str(row, "column_type"), str(row, "data_type"));
        if (!mysql) {
            type = composeType(type, str(row, "char_length"), str(row, "data_length"),
                    str(row, "data_precision"), str(row, "data_scale"));
        }
        ColumnInfo col = new ColumnInfo(column, type);
        if (mysql) {
            col.setNullable(!isNo(firstNonNull(str(row, "is_nullable"), str(row, "nullable"))));
            col.setPrimaryKey("PRI".equalsIgnoreCase(str(row, "column_key")));
            col.setAutoIncrement(containsIgnoreCase(str(row, "extra"), "auto_increment"));
            col.setComment(str(row, "column_comment"));
            col.setDefaultValue(str(row, "column_default"));
        } else {
            col.setNullable(nullableOrNull(firstNonNull(str(row, "is_nullable"), str(row, "nullable"))));
            col.setPrimaryKey(pkCols == null ? null : pkCols.containsKey(norm(column)));
            col.setAutoIncrement(identityCols == null ? null : identityCols.containsKey(norm(column)));
            col.setComment(comments == null ? null : comments.get(norm(column)));
            col.setDefaultValue(str(row, "data_default"));
        }
        return col;
    }

    /** 需要「长度」参数的类型（DM/神通字典 char_length 给出声明长度）。 */
    private static final Set<String> LENGTH_TYPES = new HashSet<>(Arrays.asList(
            "CHAR", "CHARACTER", "VARCHAR", "VARCHAR2", "NCHAR", "NVARCHAR", "NVARCHAR2",
            "CHARACTER VARYING", "NCHAR VARYING", "BINARY", "VARBINARY", "RAW"));

    /** 需要「精度[,标度]」参数的类型。 */
    private static final Set<String> PRECISION_TYPES = new HashSet<>(Arrays.asList(
            "NUMBER", "NUMERIC", "DECIMAL", "DEC"));

    /**
     * DM/神通裸类型名 → 带长度/精度的完整类型文本（已带括号或字典未给参数时原样返回）。
     *
     * <p>例：VARCHAR + char_length=255 → {@code VARCHAR(255)}；NUMBER + precision=10, scale=2 →
     * {@code NUMBER(10,2)}。整型/时间/大对象等不需要参数的类型保持不变。
     *
     * <p>长度取值：优先 char_length（DM/神通对 VARCHAR2 等给出字符长度）；为 0/缺失时退回
     * data_length —— 实测达梦 NCHAR/NVARCHAR2 的 char_length 恒为 0，而 data_length 按 UTF-8
     * 最坏 4 字节/字符计（湖南 NVARCHAR2(100 CHAR)→400、福建 NVARCHAR2(50 CHAR)→200，
     * 两库样本一致），故 N 系列取 data_length/4 还原声明字符数；非 N 系列 data_length 即声明长度。
     */
    static String composeType(String type, String charLength, String dataLength, String precision, String scale) {
        if (type == null) {
            return null;
        }
        String t = type.trim();
        if (t.indexOf('(') >= 0) {
            return t;
        }
        String upper = t.toUpperCase();
        String args = null;
        if (LENGTH_TYPES.contains(upper)) {
            Long n = asLong(charLength);
            if (n == null || n <= 0) {
                Long bytes = asLong(dataLength);
                if (bytes != null && bytes > 0) {
                    n = upper.startsWith("N") ? Math.max(1, bytes / 4) : bytes;
                }
            }
            if (n != null && n > 0) {
                args = String.valueOf(n);
            }
        } else if (PRECISION_TYPES.contains(upper)) {
            Long p = asLong(precision);
            if (p != null && p > 0) {
                Long s = asLong(scale);
                args = (s != null && s > 0) ? p + "," + s : String.valueOf(p);
            }
        }
        return args == null ? t : t + "(" + args + ")";
    }

    /** 宽松数字解析（字典返回可能是 Long / BigDecimal / 字符串）。 */
    private static Long asLong(String v) {
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        try {
            return (long) Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 执行结果 rows（过滤非 Map 行），保持行序。 */
    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> rowMaps(Map<String, Object> result) {
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

    /* ==================== 索引 / 表注释增强（字段对比快照用） ==================== */

    /**
     * 索引行 → 表名分组（保行序：表序=字典排序，索引内列按 position 升序）。
     *
     * <p>唯一性/主键判定跨方言兼容：{@code constraint_type}（DM/神通 P=主键 U=唯一）优先，
     * 其次 {@code uniqueness}(UNIQUE/NONUNIQUE)，再次 {@code non_unique}(0/1)，
     * 最后索引名 PRIMARY（MySQL 主键索引名）。命名优先取约束名——达梦唯一约束的
     * 索引名是系统生成的 INDEX33611650，约束名才是人写的 {@code uk_sys_upload_file_name}，
     * 生成的建表/补丁按约束名重建更可读。
     *
     * <p>列用 TreeMap 按 position 落位：既纠正字典未按序返回的情况，也天然丢弃 LEFT JOIN
     * 多约束产生的重复行；position 缺失时按出现顺序排在最后。
     */
    static Map<String, List<IndexInfo>> groupIndexesByTable(Map<String, Object> result) {
        Map<String, Map<String, IndexInfo>> meta = new LinkedHashMap<>();
        Map<String, Map<String, TreeMap<Integer, String>>> colsOf = new LinkedHashMap<>();
        int missingPos = 0;
        for (Map<?, ?> row : rowMaps(result)) {
            String table = str(row, "table_name");
            String index = str(row, "index_name");
            String column = str(row, "column_name");
            if (table == null || index == null || column == null || column.trim().isEmpty()) {
                continue;
            }
            String ctype = str(row, "constraint_type");
            String cname = str(row, "constraint_name");
            String key = norm(index);
            Map<String, IndexInfo> perTable = meta.computeIfAbsent(table, k -> new LinkedHashMap<>());
            if (!perTable.containsKey(key)) {
                boolean constrained = "U".equalsIgnoreCase(ctype) || "P".equalsIgnoreCase(ctype);
                IndexInfo ix = new IndexInfo();
                ix.setName(constrained && cname != null && !cname.trim().isEmpty() ? cname : index);
                boolean primary = "P".equalsIgnoreCase(ctype) || "PRIMARY".equalsIgnoreCase(index.trim());
                ix.setPrimary(primary);
                ix.setUnique(!primary && isUniqueIndexRow(row, ctype));
                ix.setType(str(row, "index_type"));
                perTable.put(key, ix);
            }
            Long pos = asLong(str(row, "column_position"));
            int p = pos == null ? Integer.MIN_VALUE + (++missingPos) : pos.intValue();
            colsOf.computeIfAbsent(table, k -> new LinkedHashMap<>())
                    .computeIfAbsent(key, k -> new TreeMap<>())
                    .putIfAbsent(p, column);
        }
        Map<String, List<IndexInfo>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, IndexInfo>> e : meta.entrySet()) {
            Map<String, TreeMap<Integer, String>> perTableCols = colsOf.get(e.getKey());
            List<IndexInfo> list = new ArrayList<>();
            for (Map.Entry<String, IndexInfo> ie : e.getValue().entrySet()) {
                IndexInfo ix = ie.getValue();
                TreeMap<Integer, String> c = perTableCols == null ? null : perTableCols.get(ie.getKey());
                if (c != null) {
                    ix.getColumns().addAll(c.values());
                }
                if (!ix.getColumns().isEmpty()) {
                    list.add(ix);   // 无列可取的索引（特殊/内部索引）不进快照
                }
            }
            out.put(e.getKey(), list);
        }
        return out;
    }

    /** 该索引行是否唯一（已排除主键；uniqueness 优先，退化到 non_unique）。 */
    private static boolean isUniqueIndexRow(Map<?, ?> row, String constraintType) {
        if ("U".equalsIgnoreCase(constraintType)) {
            return true;
        }
        String uniq = str(row, "uniqueness");
        if (uniq != null && !uniq.trim().isEmpty()) {
            return "UNIQUE".equalsIgnoreCase(uniq.trim());
        }
        String nonUnique = str(row, "non_unique");
        if (nonUnique != null && !nonUnique.trim().isEmpty()) {
            String v = nonUnique.trim();
            return "0".equals(v) || "false".equalsIgnoreCase(v);
        }
        return false;
    }

    /** 表注释行 → 表名映射（comments 或 table_comment 列皆可；空注释视为无）。 */
    static Map<String, String> groupTableComments(Map<String, Object> result) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<?, ?> row : rowMaps(result)) {
            String table = str(row, "table_name");
            if (table == null) {
                continue;
            }
            String comment = str(row, "comments");
            if (comment == null) {
                comment = str(row, "table_comment");
            }
            if (comment != null && !comment.trim().isEmpty()) {
                out.put(table, comment);
            }
        }
        return out;
    }

    /** 全库索引映射（表名 → 索引清单）；方言不支持或查询失败返回 null（快照不含索引，前端不生成）。 */
    private Map<String, List<IndexInfo>> loadIndexesByTable(DatabaseConnection conn, Dialect dialect, String schema) {
        String sql = dialect.schemaAllIndexesQuery();
        if (sql == null) {
            return null;
        }
        try {
            return groupIndexesByTable(executor.executeRaw(conn, sql, Collections.singletonList(schema)));
        } catch (Exception e) {
            log.warn("[SchemaService] 索引增强查询失败将降级 dbType={}: {}", conn.getDbType(), e.getMessage());
            return null;
        }
    }

    /** 全库表注释映射（表名 → 注释）；方言不支持或查询失败返回 null。 */
    private Map<String, String> loadTableComments(DatabaseConnection conn, Dialect dialect, String schema) {
        String sql = dialect.schemaAllTableCommentsQuery();
        if (sql == null) {
            return null;
        }
        try {
            return groupTableComments(executor.executeRaw(conn, sql, Collections.singletonList(schema)));
        } catch (Exception e) {
            log.warn("[SchemaService] 表注释增强查询失败将降级 dbType={}: {}", conn.getDbType(), e.getMessage());
            return null;
        }
    }

    /** 把索引/表注释挂到表上（缺失即保持空，不影响列主流程）。 */
    private void enrichTableMeta(TableInfo info, Map<String, List<IndexInfo>> ixByTable, Map<String, String> cmtByTable) {
        if (info == null) {
            return;
        }
        if (ixByTable != null) {
            List<IndexInfo> ix = ixByTable.get(info.getName());
            if (ix != null) {
                info.setIndexes(ix);
            }
        }
        if (cmtByTable != null) {
            String comment = cmtByTable.get(info.getName());
            if (comment != null) {
                info.setComment(comment);
            }
        }
    }

    /** 执行结果 rows 里取 key 列 → value 列的小写规范化映射（用于注释探测）。 */    @SuppressWarnings("unchecked")
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
    private static String str(Map<?, ?> row, String key) {
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