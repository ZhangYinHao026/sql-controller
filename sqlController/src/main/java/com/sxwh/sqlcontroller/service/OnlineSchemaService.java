package com.sxwh.sqlcontroller.service;

import com.sxwh.sqlcontroller.config.AutocompleteProperties;
import com.sxwh.sqlcontroller.dialect.Dialect;
import com.sxwh.sqlcontroller.dialect.DialectRegistry;
import com.sxwh.sqlcontroller.model.ColumnInfo;
import com.sxwh.sqlcontroller.model.IndexInfo;
import com.sxwh.sqlcontroller.model.OnlineConnection;
import com.sxwh.sqlcontroller.model.SchemaSnapshot;
import com.sxwh.sqlcontroller.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线上连接的结构快照（字段对比用）：与本地 {@link SchemaService#snapshot} 同构的
 * {@link SchemaSnapshot}，但元数据经<b>远程查询小助手</b>只读抓取，绝不直连线上库。
 *
 * <p>实测约束（2026-10-23，别改错）：</p>
 * <ul>
 *   <li><b>与表数据量无关</b>：只读数据字典（MySQL=information_schema，达梦/神通=all_tab_columns 系），
 *       万级字典行也只需几百毫秒级；成本只来自「页数 × 每页 500 行」的小助手往返。</li>
 *   <li><b>小助手禁止 SQL 里写 LIMIT</b>（实测 {@code ... LIMIT 500} → code 300），分页只能靠
 *       {@code pagesize/pageindex}；{@code pagesize=500} 生效且 {@code pagecount} 准确。</li>
 *   <li><b>小助手不支持 {@code ?} 参数占位</b> → 方言字典 SQL 的参数必须内联：
 *       schema 位（MySQL={@code database()}；达梦/神通={@code USER}）、表名位=单引号字面量（'' 转义）。</li>
 *   <li>行映射/组装逻辑与本地共用 {@link SchemaService} 的包内静态助手（单一真相，防止两套口径漂移）。</li>
 * </ul>
 */
@Service
public class OnlineSchemaService {
    private static final Logger log = LoggerFactory.getLogger(OnlineSchemaService.class);

    /** 小助手单页行数上限（实测 500 生效；再大未验证，不冒险）。 */
    private static final int PAGE_SIZE = 500;
    /** 分页保护：单条字典查询最多翻这么多页（500 × 400 = 20 万行，远超任何库的字典规模）。 */
    private static final int MAX_PAGES = 400;

    private final OnlineConnectionService connections;
    private final OnlineQueryService query;
    private final DialectRegistry registry;
    private final AutocompleteProperties props;

    /** 快照缓存：线上连接 id → (快照, 过期时间)，TTL 与本地快照一致（autocomplete.schema-cache-seconds）。 */
    private final Map<Long, SnapshotEntry> cache = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newFixedThreadPool(8, new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(1);
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "online-schema-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    public OnlineSchemaService(OnlineConnectionService connections, OnlineQueryService query,
                               DialectRegistry registry, AutocompleteProperties props) {
        this.connections = connections;
        this.query = query;
        this.registry = registry;
        this.props = props;
    }

    /** 批量抓取（基准 + 比对库），并发 8，单库失败转 ok=false 不拖累其它库；返回顺序=入参顺序。 */
    public List<SchemaSnapshot> snapshots(List<Long> ids, boolean force, List<String> tableFilter) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        List<CompletableFuture<SchemaSnapshot>> futures = new ArrayList<>(ids.size());
        for (Long id : ids) {
            if (id == null) continue;
            futures.add(CompletableFuture.supplyAsync(() -> snapshot(id, force, tableFilter), executor));
        }
        List<SchemaSnapshot> out = new ArrayList<>(futures.size());
        for (CompletableFuture<SchemaSnapshot> f : futures) out.add(f.join());
        return out;
    }

    /**
     * 抓单个线上连接的结构快照。
     *
     * @param tableNames 非空 = 局部刷新（执行补丁后只抓这些表，跳过缓存不写缓存）
     */
    public SchemaSnapshot snapshot(Long connectionId, boolean force, List<String> tableNames) {
        if (connectionId == null) return fail(null, null, "连接 id 为空");
        boolean filtered = tableNames != null && !tableNames.isEmpty();
        if (!filtered && !force) {
            SnapshotEntry entry = cache.get(connectionId);
            if (entry != null && !entry.isExpired()) return entry.data;
        }
        OnlineConnection conn;
        try {
            conn = connections.get(connectionId);
        } catch (Exception e) {
            return fail(connectionId, null, "线上连接不存在或已删除");
        }
        if (conn == null) return fail(connectionId, null, "线上连接不存在或已删除");
        String dbType = conn.getDbType() == null ? "mysql" : conn.getDbType();
        try {
            Dialect dialect = registry.get(dbType);
            boolean mysql = "mysql".equalsIgnoreCase(dbType);
            String schemaExpr = mysql ? "database()" : "USER";
            SchemaSnapshot snap = new SchemaSnapshot();
            snap.setConnectionId(connectionId);
            snap.setDbType(dbType);
            snap.setDatabaseName(conn.getRegionName());
            snap.setFetchedAt(System.currentTimeMillis());
            snap.setOk(true);
            List<TableInfo> tables = filtered
                    ? loadFiltered(conn, dialect, schemaExpr, mysql, tableNames)
                    : loadAll(conn, dialect, schemaExpr, mysql);
            snap.setTables(tables);
            if (!filtered) {
                cache.put(connectionId, new SnapshotEntry(snap, System.currentTimeMillis() + props.getSchemaCacheSeconds() * 1000L));
            }
            log.info("[online-schema] snapshot area={} {} type={} tables={} filtered={}",
                    conn.getAreaCode(), conn.getRegionName(), dbType, tables.size(), filtered);
            return snap;
        } catch (Exception e) {
            String msg = e.getMessage() == null || e.getMessage().trim().isEmpty() ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[online-schema] 快照失败 area={} {}: {}", conn.getAreaCode(), conn.getRegionName(), msg);
            return fail(connectionId, dbType, "读取结构失败: " + msg);
        }
    }

    /** 全量：表清单 + 批量列/主键/注释/自增/索引/表注释（增强查询失败降级为 null，与本地口径一致）。 */
    private List<TableInfo> loadAll(OnlineConnection conn, Dialect dialect, String schemaExpr, boolean mysql) {
        List<String> tableNames = SchemaService.readTableNamesRows(fetchAll(conn, inline(dialect.schemaTablesQuery(), schemaExpr)));
        log.info("[online-schema] step1 area={} tables={} dbType={}", conn.getAreaCode(), tableNames.size(), conn.getDbType());

        Map<String, List<? extends Map<?, ?>>> colRows;
        String allColsSql = dialect.schemaAllColumnsQuery();
        if (allColsSql != null) {
            colRows = new LinkedHashMap<>(SchemaService.groupRowsByTableRows(fetchAll(conn, inline(allColsSql, schemaExpr))));
        } else {
            colRows = new LinkedHashMap<>();
            for (String t : tableNames) {
                colRows.put(t, fetchAll(conn, inline(dialect.schemaColumnDetailQuery(), schemaExpr, t)));
            }
        }

        Map<String, Map<String, Boolean>> pkByTable = null;
        Map<String, Map<String, String>> commentByTable = null;
        Map<String, Map<String, Boolean>> identityByTable = null;
        Map<String, List<IndexInfo>> indexByTable = null;
        Map<String, String> tableCommentByTable = null;
        if (!mysql) {
            String pkSql = dialect.schemaAllPkColumnsQuery();
            if (pkSql != null) {
                try {
                    pkByTable = SchemaService.groupNameSetByTableRows(fetchAll(conn, inline(pkSql, schemaExpr)));
                } catch (Exception e) {
                    log.warn("[online-schema] 主键增强失败将降级 area={} dbType={}: {}", conn.getAreaCode(), conn.getDbType(), msg(e));
                }
            }
            String cmtSql = dialect.schemaAllColumnCommentsQuery();
            if (cmtSql != null) {
                try {
                    commentByTable = SchemaService.groupStringMapByTableRows(fetchAll(conn, inline(cmtSql, schemaExpr)));
                } catch (Exception e) {
                    log.warn("[online-schema] 列注释增强失败将降级 area={} dbType={}: {}", conn.getAreaCode(), conn.getDbType(), msg(e));
                }
            }
            String idSql = dialect.schemaAllIdentityColumnsQuery();
            if (idSql != null) {
                try {
                    identityByTable = SchemaService.groupNameSetByTableRows(fetchAll(conn, inline(idSql, schemaExpr)));
                } catch (Exception e) {
                    log.warn("[online-schema] 自增增强失败将降级 area={} dbType={}: {}", conn.getAreaCode(), conn.getDbType(), msg(e));
                }
            }
        }
        String ixSql = dialect.schemaAllIndexesQuery();
        if (ixSql != null) {
            try {
                indexByTable = SchemaService.groupIndexesByTableRows(fetchAll(conn, inline(ixSql, schemaExpr)));
            } catch (Exception e) {
                log.warn("[online-schema] 索引增强失败将降级 area={} dbType={}: {}", conn.getAreaCode(), conn.getDbType(), msg(e));
            }
        }
        String tcSql = dialect.schemaAllTableCommentsQuery();
        if (tcSql != null) {
            try {
                tableCommentByTable = SchemaService.groupTableCommentsRows(fetchAll(conn, inline(tcSql, schemaExpr)));
            } catch (Exception e) {
                log.warn("[online-schema] 表注释增强失败将降级 area={} dbType={}: {}", conn.getAreaCode(), conn.getDbType(), msg(e));
            }
        }
        return assemble(tableNames, colRows, mysql, pkByTable, commentByTable, identityByTable, indexByTable, tableCommentByTable);
    }

    /** 局部刷新：逐表走单表详情/主键/注释查询（与本地 loadSnapshotTablesFiltered 同语义），不写缓存。 */
    private List<TableInfo> loadFiltered(OnlineConnection conn, Dialect dialect, String schemaExpr,
                                         boolean mysql, List<String> tableNames) {
        Map<String, List<IndexInfo>> indexByTable = null;
        Map<String, String> tableCommentByTable = null;
        String ixSql = dialect.schemaAllIndexesQuery();
        if (ixSql != null) {
            try {
                indexByTable = SchemaService.groupIndexesByTableRows(fetchAll(conn, inline(ixSql, schemaExpr)));
            } catch (Exception e) {
                log.warn("[online-schema] 局部刷新索引增强失败将降级 area={}: {}", conn.getAreaCode(), msg(e));
            }
        }
        String tcSql = dialect.schemaAllTableCommentsQuery();
        if (tcSql != null) {
            try {
                tableCommentByTable = SchemaService.groupTableCommentsRows(fetchAll(conn, inline(tcSql, schemaExpr)));
            } catch (Exception e) {
                log.warn("[online-schema] 局部刷新表注释增强失败将降级 area={}: {}", conn.getAreaCode(), msg(e));
            }
        }
        List<TableInfo> out = new ArrayList<>(tableNames.size());
        for (String tableName : tableNames) {
            if (SchemaService.isSystemTable(tableName)) continue;
            try {
                Map<String, Boolean> pkCols = null;
                Map<String, String> comments = null;
                if (!mysql) {
                    String pkSql = dialect.schemaPkColumnsQuery();
                    if (pkSql != null) {
                        try {
                            pkCols = SchemaService.queryNameSetRows(fetchAll(conn, inline(pkSql, schemaExpr, tableName)), "column_name");
                        } catch (Exception e) {
                            log.warn("[online-schema] 局部刷新主键增强失败将降级 table={}: {}", tableName, msg(e));
                        }
                    }
                    String cmtSql = dialect.schemaColumnCommentQuery();
                    if (cmtSql != null) {
                        try {
                            comments = SchemaService.queryStringMapRows(fetchAll(conn, inline(cmtSql, schemaExpr, tableName)), "column_name", new String[]{"comments", "comment"});
                        } catch (Exception e) {
                            log.warn("[online-schema] 局部刷新注释增强失败将降级 table={}: {}", tableName, msg(e));
                        }
                    }
                }
                TableInfo info = new TableInfo(tableName);
                for (Map<?, ?> row : fetchAll(conn, inline(dialect.schemaColumnDetailQuery(), schemaExpr, tableName))) {
                    ColumnInfo col = SchemaService.toColumnInfo(row, mysql, pkCols, comments, null);
                    if (col != null) info.getColumns().add(col);
                }
                if (!info.getColumns().isEmpty()) {
                    SchemaService.enrichTableMeta(info, indexByTable, tableCommentByTable);
                    out.add(info);
                }
            } catch (Exception e) {
                log.warn("[online-schema] 局部刷新表 {} 失败: {}", tableName, msg(e));
            }
        }
        return out;
    }

    /** 表清单 + 各映射 → TableInfo 列表（与本地批量路径同口径：maxTables 截断、系统表过滤、组内保持列序）。 */
    private List<TableInfo> assemble(List<String> tableNames, Map<String, List<? extends Map<?, ?>>> colRows, boolean mysql,
                                     Map<String, Map<String, Boolean>> pkByTable,
                                     Map<String, Map<String, String>> commentByTable,
                                     Map<String, Map<String, Boolean>> identityByTable,
                                     Map<String, List<IndexInfo>> indexByTable,
                                     Map<String, String> tableCommentByTable) {
        Map<String, TableInfo> byName = new LinkedHashMap<>();
        int truncated = 0;
        for (String tableName : tableNames) {
            if (byName.size() >= Math.max(1, props.getMaxTables())) {
                truncated += tableNames.size() - byName.size();
                break;
            }
            if (SchemaService.isSystemTable(tableName)) continue;
            try {
                TableInfo info = new TableInfo(tableName);
                Map<String, Boolean> pkCols = pkByTable == null ? null : pkByTable.get(tableName);
                Map<String, String> comments = commentByTable == null ? null : commentByTable.get(tableName);
                Map<String, Boolean> identityCols = identityByTable == null ? null : identityByTable.get(tableName);
                for (Map<?, ?> row : colRows.getOrDefault(tableName, Collections.<Map<String, Object>>emptyList())) {
                    ColumnInfo col = SchemaService.toColumnInfo(row, mysql, pkCols, comments, identityCols);
                    if (col != null) info.getColumns().add(col);
                }
                SchemaService.enrichTableMeta(info, indexByTable, tableCommentByTable);
                byName.put(tableName, info);
            } catch (Exception e) {
                log.warn("[online-schema] 表 {} 组装失败: {}", tableName, msg(e));
            }
        }
        if (truncated > 0) log.warn("[online-schema] maxTables={} 截断，跳过 {} 张表", props.getMaxTables(), truncated);
        return new ArrayList<>(byName.values());
    }

    /** 小助手分页抓全：pagesize=500 从第 1 页翻到 pagecount（上限 {@link #MAX_PAGES}）。 */
    private List<? extends Map<String, Object>> fetchAll(OnlineConnection conn, String sql) {
        OnlineQueryService.Rows first = query.selectRows(conn, sql, 1, PAGE_SIZE);
        List<Map<String, Object>> out = new ArrayList<>();
        out.addAll(first.rows);
        int pages = Math.min(Math.max(1, first.pageCount), MAX_PAGES);
        for (int p = 2; p <= pages; p++) {
            out.addAll(query.selectRows(conn, sql, p, PAGE_SIZE).rows);
        }
        return out;
    }

    /**
     * 把方言字典 SQL 的 {@code ?} 占位内联成小助手可执行的纯文本。
     *
     * @param schemaExpr schema 位表达式（MySQL={@code database()}；达梦/神通={@code USER}）
     * @param literals   后续 {@code ?} 依次对应的字符串字面量（自动单引号包裹 + '' 转义）
     */
    static String inline(String sql, String schemaExpr, String... literals) {
        if (sql == null) return null;
        StringBuilder sb = new StringBuilder(sql.length() + 32);
        int li = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch != '?') {
                sb.append(ch);
                continue;
            }
            if (li == 0) {
                sb.append(schemaExpr == null ? "''" : schemaExpr);
            } else if (li - 1 < literals.length) {
                sb.append(quote(literals[li - 1]));
            } else {
                throw new IllegalArgumentException("字典 SQL 占位符多于参数：" + sql);
            }
            li++;
        }
        if (literals.length > Math.max(0, li - 1)) {
            throw new IllegalArgumentException("字典 SQL 参数多于占位符：" + sql);
        }
        return sb.toString();
    }

    /** 字符串 → SQL 单引号字面量（'' 转义；null 按 NULL 处理）。 */
    static String quote(String v) {
        if (v == null) return "NULL";
        return "'" + v.replace("'", "''") + "'";
    }

    private static SchemaSnapshot fail(Long connectionId, String dbType, String message) {
        SchemaSnapshot snap = new SchemaSnapshot();
        snap.setConnectionId(connectionId);
        snap.setDbType(dbType);
        snap.setOk(false);
        snap.setError(message);
        return snap;
    }

    private static String msg(Throwable e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
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

    @javax.annotation.PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
