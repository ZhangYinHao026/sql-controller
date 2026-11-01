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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 行级增删改（本地直连）：单元格编辑 / 新增行 / 删除行的执行链路。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>{@code sql-controller.write.enabled=false}（默认）时一律 403，前端也不渲染编辑入口；</li>
 *   <li>{@code dryRun=true} 只拼 SQL 不执行——确认框里展示的就是这条，保证「所见即所执行」；</li>
 *   <li>无论成功失败都写 {@code sql_execution_history}（含操作人 IP），便于事后追责。</li>
 * </ol>
 */
@Service
public class RowWriteService {

    private static final Logger log = LoggerFactory.getLogger(RowWriteService.class);

    private final DatabaseConnectionService connections;
    private final DynamicDataSourceService executor;
    private final TableBrowseService browse;
    private final DialectRegistry dialects;
    private final SqlControllerProperties properties;
    private final HistoryRepository history;

    public RowWriteService(DatabaseConnectionService connections, DynamicDataSourceService executor,
                           TableBrowseService browse, DialectRegistry dialects,
                           SqlControllerProperties properties, HistoryRepository history) {
        this.connections = connections;
        this.executor = executor;
        this.browse = browse;
        this.dialects = dialects;
        this.properties = properties;
        this.history = history;
    }

    /** 本地写入口是否开启。 */
    public boolean localEnabled() {
        return properties.getWrite().isEnabled();
    }

    /** 线上写入口是否开启。 */
    public boolean onlineEnabled() {
        return properties.getWrite().isOnlineEnabled();
    }

    // ==================== 三种写操作 ====================

    public Map<String, Object> update(Map<String, Object> body, String clientIp) {
        requireEnabled();
        DatabaseConnection conn = requireConnection(body);
        TableInfo table = browse.requireTable(conn, asString(body.get("table")), true);
        Dialect dialect = dialects.get(conn.getDbType());
        Map<String, Object> pk = mapOf(body.get("pk"));
        Map<String, Object> changes = mapOf(body.get("changes"));
        RowSqlBuilder.Mutation mutation = RowSqlBuilder.update(dialect, table, pk, changes, false);
        String display = truthy(body.get("dryRun"))
                ? RowSqlBuilder.update(dialect, table, pk, changes, true).sql : mutation.sql;
        return run(conn, table, mutation, display, body, clientIp);
    }

    public Map<String, Object> insert(Map<String, Object> body, String clientIp) {
        requireEnabled();
        DatabaseConnection conn = requireConnection(body);
        TableInfo table = browse.requireTable(conn, asString(body.get("table")), true);
        Dialect dialect = dialects.get(conn.getDbType());
        Map<String, Object> values = mapOf(body.get("values"));
        RowSqlBuilder.Mutation mutation = RowSqlBuilder.insert(dialect, table, values, false);
        String display = truthy(body.get("dryRun"))
                ? RowSqlBuilder.insert(dialect, table, values, true).sql : mutation.sql;
        return run(conn, table, mutation, display, body, clientIp);
    }

    public Map<String, Object> delete(Map<String, Object> body, String clientIp) {
        requireEnabled();
        DatabaseConnection conn = requireConnection(body);
        TableInfo table = browse.requireTable(conn, asString(body.get("table")), true);
        Dialect dialect = dialects.get(conn.getDbType());
        Map<String, Object> pk = mapOf(body.get("pk"));
        RowSqlBuilder.Mutation mutation = RowSqlBuilder.delete(dialect, table, pk, false);
        String display = truthy(body.get("dryRun"))
                ? RowSqlBuilder.delete(dialect, table, pk, true).sql : mutation.sql;
        return run(conn, table, mutation, display, body, clientIp);
    }

    // ==================== 执行 + 审计 ====================

    /**
     * @param displaySql 给确认框展示的 SQL：dryRun 时把值内联成字面量，
     *                   让用户看到「改成了什么」，而不是一排 {@code ?}
     */
    private Map<String, Object> run(DatabaseConnection conn, TableInfo table, RowSqlBuilder.Mutation mutation,
                                    String displaySql, Map<String, Object> body, String clientIp) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sql", displaySql);
        out.put("preparedSql", mutation.sql);
        out.put("kind", mutation.kind);
        out.put("table", table.getName());
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
            Map<String, Object> result = executor.execute(conn, mutation.sql, mutation.params, 1, 10, true);
            affected = asLong(result.get("affectedRows"));
            if (affected <= 0) {
                status = "EMPTY";
            }
        } catch (Exception e) {
            status = "FAILED";
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[row-write] {} {} 失败 connectionId={} table={}: {}",
                    mutation.kind, conn.getId(), conn.getId(), table.getName(), error);
        }
        long duration = System.currentTimeMillis() - started;
        history.save(requestId, conn.getId(), null, null, conn.getDbType(), conn.getRegionName(),
                mutation.sql, mutation.kind, "TABLE_GRID", status, affected, clientIp, duration, error);

        out.put("durationMs", duration);
        out.put("affectedRows", affected);
        if ("FAILED".equals(status)) {
            out.put("ok", false);
            out.put("message", "执行失败：" + error);
        } else if (affected <= 0) {
            out.put("ok", false);
            out.put("message", "DELETE".equals(mutation.kind)
                    ? "没有匹配的行，记录可能已被他人删除，请刷新"
                    : "没有行被改动：记录可能已被他人修改/删除，或新值与旧值相同");
        } else {
            out.put("message", mutation.kind + " 成功，影响 " + affected + " 行");
        }
        return out;
    }

    // ==================== 内部工具 ====================

    private void requireEnabled() {
        if (!localEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "写入口未开启：请在 application.yml 将 sql-controller.write.enabled 置为 true 后重启服务");
        }
    }

    private DatabaseConnection requireConnection(Map<String, Object> body) {
        Long id = asLong(body.get("connectionId"));
        if (id == null) {
            throw new IllegalArgumentException("connectionId 不能为空");
        }
        DatabaseConnection conn = connections.get(id);
        if (conn == null) {
            throw new IllegalArgumentException("连接不存在: " + id);
        }
        return conn;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<>();
    }

    private boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
