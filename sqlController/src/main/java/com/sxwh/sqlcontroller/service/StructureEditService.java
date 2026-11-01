package com.sxwh.sqlcontroller.service;

import com.sxwh.sqlcontroller.config.SqlControllerProperties;
import com.sxwh.sqlcontroller.dialect.Dialect;
import com.sxwh.sqlcontroller.dialect.DialectRegistry;
import com.sxwh.sqlcontroller.model.DatabaseConnection;
import com.sxwh.sqlcontroller.model.TableInfo;
import com.sxwh.sqlcontroller.repository.HistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 结构编辑：把前端「字段表改了什么」翻成 DDL 并执行。
 *
 * <p>两段式（与行级写入同构）：
 * <ul>
 *   <li>{@code preview}：只生成语句，给前端「DDL 预览区」展示，用户可手改；</li>
 *   <li>{@code execute}：必须带 {@code confirmed=true}，逐条执行，遇错即停并回报第几条失败。</li>
 * </ul>
 * MySQL/DM/OSCAR 的 DDL 都会隐式提交，因此这里不做事务包装——一旦中途失败，前面已执行的语句无法回滚，
 * 所以返回体里明确给出「已执行 N / 共 M 条」。
 */
@Service
public class StructureEditService {

    private static final Logger log = LoggerFactory.getLogger(StructureEditService.class);

    private final DatabaseConnectionService connections;
    private final DynamicDataSourceService executor;
    private final TableBrowseService browse;
    private final SchemaService schemas;
    private final DialectRegistry dialects;
    private final SqlControllerProperties properties;
    private final HistoryRepository history;

    public StructureEditService(DatabaseConnectionService connections, DynamicDataSourceService executor,
                                TableBrowseService browse, SchemaService schemas, DialectRegistry dialects,
                                SqlControllerProperties properties, HistoryRepository history) {
        this.connections = connections;
        this.executor = executor;
        this.browse = browse;
        this.schemas = schemas;
        this.dialects = dialects;
        this.properties = properties;
        this.history = history;
    }

    /** 只生成 DDL，不执行。 */
    public Map<String, Object> preview(Map<String, Object> body) {
        requireEnabled();
        Context context = context(body);
        DbAlterBuilder.Plan plan = DbAlterBuilder.build(context.table, context.dialect, context.mysql, ops(body));
        Map<String, Object> out = DbAlterBuilder.describe(plan);
        out.put("table", context.table.getName());
        out.put("dbType", context.connection.getDbType());
        if (plan.statements.isEmpty()) {
            out.put("warnings", Collections.singletonList("没有检测到结构改动"));
        }
        return out;
    }

    /** 执行 DDL：逐条跑，遇错即停。 */
    public Map<String, Object> execute(Map<String, Object> body, String clientIp) {
        requireEnabled();
        boolean confirmed = Boolean.TRUE.equals(body.get("confirmed"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("confirmed")));
        if (!confirmed) {
            throw new IllegalStateException("结构变更必须确认后才能执行");
        }
        Context context = context(body);

        // 允许前端直接传手改后的 SQL（DDL 预览区可编辑）；否则按操作清单现场生成
        List<String> statements = explicitStatements(body);
        List<String> warnings = new ArrayList<>();
        if (statements.isEmpty()) {
            DbAlterBuilder.Plan plan = DbAlterBuilder.build(context.table, context.dialect, context.mysql, ops(body));
            warnings.addAll(plan.warnings);
            for (DbAlterBuilder.Statement statement : plan.statements) {
                statements.add(statement.sql);
            }
        }
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("没有需要执行的 DDL");
        }

        String requestId = UUID.randomUUID().toString();
        long started = System.currentTimeMillis();
        List<Map<String, Object>> executed = new ArrayList<>(statements.size());
        int failedIndex = -1;
        String error = null;
        for (int i = 0; i < statements.size(); i++) {
            String sql = statements.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sql", sql);
            try {
                Map<String, Object> result = executor.execute(context.connection, sql, Collections.emptyList(), 1, 10, false);
                item.put("ok", true);
                item.put("affectedRows", result.get("affectedRows"));
            } catch (Exception e) {
                item.put("ok", false);
                item.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                failedIndex = i;
                error = String.valueOf(item.get("error"));
                executed.add(item);
                log.warn("[structure] 第 {} 条 DDL 失败 connectionId={} table={}: {}",
                        i + 1, context.connection.getId(), context.table.getName(), error);
                break;
            }
            executed.add(item);
        }
        long duration = System.currentTimeMillis() - started;

        String joined = String.join(";\n", statements);
        history.save(requestId, context.connection.getId(), null, null, context.connection.getDbType(),
                context.connection.getRegionName(), joined, "DDL", "TABLE_STRUCT", error == null ? "SUCCESS" : "FAILED",
                executed.size(), clientIp, duration, error);
        // 结构已变：精确清这张表的缓存（其他表不用重拉）+ 联想缓存（加了字段编辑器要能联想到）
        schemas.evictTable(context.connection.getId(), context.table.getName());
        schemas.evictAutocomplete(context.connection.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", error == null);
        out.put("executed", executed.size());
        out.put("total", statements.size());
        out.put("failedIndex", failedIndex);
        out.put("durationMs", duration);
        out.put("statements", executed);
        out.put("warnings", warnings);
        out.put("message", error == null
                ? "结构变更已执行（" + statements.size() + " 条）"
                : "第 " + (failedIndex + 1) + " 条执行失败：" + error + "；此前 " + failedIndex + " 条已生效");
        return out;
    }

    // ==================== 内部 ====================

    private static final class Context {
        final DatabaseConnection connection;
        final TableInfo table;
        final Dialect dialect;
        final boolean mysql;

        Context(DatabaseConnection connection, TableInfo table, Dialect dialect, boolean mysql) {
            this.connection = connection;
            this.table = table;
            this.dialect = dialect;
            this.mysql = mysql;
        }
    }

    private Context context(Map<String, Object> body) {
        Long id = asLong(body.get("connectionId"));
        if (id == null) {
            throw new IllegalArgumentException("connectionId 不能为空");
        }
        DatabaseConnection conn = connections.get(id);
        if (conn == null) {
            throw new IllegalArgumentException("连接不存在: " + id);
        }
        TableInfo table = browse.requireTable(conn, asString(body.get("table")), true);
        Dialect dialect = dialects.get(conn.getDbType());
        return new Context(conn, table, dialect, DbDdlBuilder.isMysql(conn.getDbType()));
    }

    private void requireEnabled() {
        if (!properties.getWrite().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "结构编辑未开启：请在 application.yml 将 sql-controller.write.enabled 置为 true 后重启服务");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> ops(Map<String, Object> body) {
        Object value = body.get("ops");
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return Collections.emptyList();
    }

    private List<String> explicitStatements(Map<String, Object> body) {
        List<String> out = new ArrayList<>();
        Object value = body.get("statements");
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

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
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
}
