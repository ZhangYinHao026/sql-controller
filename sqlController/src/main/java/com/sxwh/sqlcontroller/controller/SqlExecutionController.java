package com.sxwh.sqlcontroller.controller;

import com.sxwh.sqlcontroller.model.DatabaseConnection;
import com.sxwh.sqlcontroller.model.ExecuteRequest;
import com.sxwh.sqlcontroller.model.ScriptNode;
import com.sxwh.sqlcontroller.config.SqlControllerProperties;
import com.sxwh.sqlcontroller.repository.HistoryRepository;
import com.sxwh.sqlcontroller.service.DatabaseConnectionService;
import com.sxwh.sqlcontroller.service.DynamicDataSourceService;
import com.sxwh.sqlcontroller.service.ScriptNodeService;
import com.sxwh.sqlcontroller.service.ScriptParameterService;
import com.sxwh.sqlcontroller.service.SqlExecutionCancellation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 执行编辑器 SQL 或侧边栏脚本，并按连接分别返回结果。 */
@RestController
@RequestMapping("/api/sql")
@CrossOrigin
public class SqlExecutionController {
    private static final Logger log = LoggerFactory.getLogger(SqlExecutionController.class);

    private final DatabaseConnectionService connections;
    private final DynamicDataSourceService executor;
    private final HistoryRepository history;
    private final ScriptNodeService scriptNodes;
    private final ScriptParameterService parameterService;
    private final SqlControllerProperties properties;
    private final SqlExecutionCancellation cancellation;

    public SqlExecutionController(DatabaseConnectionService connections, DynamicDataSourceService executor,
                                  HistoryRepository history, ScriptNodeService scriptNodes,
                                  ScriptParameterService parameterService, SqlControllerProperties properties,
                                  SqlExecutionCancellation cancellation) {
        this.connections = connections;
        this.executor = executor;
        this.history = history;
        this.scriptNodes = scriptNodes;
        this.parameterService = parameterService;
        this.properties = properties;
        this.cancellation = cancellation;
    }

    /**
     * 执行编辑器 SQL 或已保存脚本。
     * 带 scriptNodeId 时必须读取服务端保存的脚本，客户端 SQL 仅用于一致性校验，不能替代脚本内容。
     */
    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody ExecuteRequest request) {
        if (request.getConnectionIds() == null || request.getConnectionIds().isEmpty()) {
            throw new IllegalArgumentException("至少选择一个连接");
        }

        ScriptNode script = null;
        String sourceSql = request.getSql();
        if (sourceSql == null || sourceSql.trim().isEmpty()) throw new IllegalArgumentException("SQL 不能为空");
        if (request.getScriptNodeId() == null && request.getParams() != null && !request.getParams().isEmpty()) {
            throw new IllegalArgumentException("只有脚本执行可以传入参数");
        }

        ScriptParameterService.BoundSql bound = request.getScriptNodeId() == null
                ? new ScriptParameterService.BoundSql(sourceSql, Collections.emptyList(), Collections.<ScriptParameterService.ScriptParameter>emptyList())
                : parameterService.bind(sourceSql, request.getParams());
        String sqlType = bound.getSql().trim().split("\\s+")[0].toUpperCase();
        boolean write = Arrays.asList("INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE", "CREATE").contains(sqlType);
        if (write && !Boolean.TRUE.equals(request.getConfirmed())) throw new IllegalStateException("写操作必须确认");

        String requestId = request.getRequestId() == null ? UUID.randomUUID().toString() : request.getRequestId();
        String executionMode = request.getExecutionMode() == null
                ? (script == null ? "ALL" : "SCRIPT") : request.getExecutionMode();
        Map<String, Object> response = new LinkedHashMap<>();
        List<Object> results = new ArrayList<>();
        String dbType = null;
        int success = 0;
        int page = request.getPage() == null ? 1 : Math.max(1, request.getPage());
        int pageSize = request.getPageSize() == null ? properties.getDefaultPageSize() : request.getPageSize();
        // 绑定当前请求：执行服务据此注册活跃语句，取消线程可跨线程中止；请求结束统一清理。
        boolean cancelledFlag = false;
        cancellation.bind(requestId);
        try {
            for (Long connectionId : request.getConnectionIds()) {
                // 取消可能发生在库与库之间的间隙：下一轮循环前检查标记，不再执行剩余库
                if (cancellation.isCancelled(requestId)) break;
                DatabaseConnection connection = connections.get(connectionId);
                if (dbType != null && !dbType.equalsIgnoreCase(connection.getDbType())) throw new IllegalArgumentException("不能混选不同数据库类型");
                dbType = connection.getDbType();
                long started = System.currentTimeMillis();
                // 内部开发平台：日志保留完整 SQL 与参数，便于定位数据库执行问题。
                log.info("[SQL-EXEC] requestId={} connectionId={} dbType={} region={} sqlType={} params={} sql={}",
                        requestId, connectionId, connection.getDbType(), connection.getRegionName(), sqlType,
                        request.getParams(), bound.getSql());
                try {
                    Map<String, Object> item = executor.execute(connection, bound.getSql(), bound.getValues(), page, pageSize,
                            Boolean.TRUE.equals(request.getTransactional()));
                    item.put("connectionId", connectionId);
                    item.put("dbType", connection.getDbType());
                    item.put("regionName", connection.getRegionName());
                    item.put("status", "SUCCESS");
                    history.save(requestId, connectionId, script == null ? null : script.getId(), script == null ? null : script.getName(),
                            connection.getDbType(), connection.getRegionName(), sourceSql, sqlType, executionMode, "SUCCESS",
                            ((Number) item.get("affectedRows")).longValue(), System.currentTimeMillis() - started, null);
                    results.add(item);
                    success++;
                } catch (Exception exception) {
                    // 被用户取消的库不算普通失败：标记 CANCELLED 并停止执行剩余库
                    if (cancellation.isCancelled(requestId)) {
                        String detail = "查询已被用户取消" + (exception.getMessage() == null ? "" : "（" + exception.getMessage() + "）");
                        history.save(requestId, connectionId, script == null ? null : script.getId(), script == null ? null : script.getName(),
                                connection.getDbType(), connection.getRegionName(), sourceSql, sqlType, executionMode, "FAILED",
                                0, System.currentTimeMillis() - started, detail);
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("connectionId", connectionId);
                        item.put("dbType", connection.getDbType());
                        item.put("regionName", connection.getRegionName());
                        item.put("status", "CANCELLED");
                        item.put("errorMessage", detail);
                        results.add(item);
                        break;
                    }
                    history.save(requestId, connectionId, script == null ? null : script.getId(), script == null ? null : script.getName(),
                            connection.getDbType(), connection.getRegionName(), sourceSql, sqlType, executionMode, "FAILED",
                            0, System.currentTimeMillis() - started, exception.getMessage());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("connectionId", connectionId);
                    item.put("dbType", connection.getDbType());
                    item.put("regionName", connection.getRegionName());
                    item.put("status", "FAILED");
                    item.put("errorMessage", exception.getMessage());
                    results.add(item);
                }
            }
        } finally {
            cancelledFlag = cancellation.isCancelled(requestId);
            cancellation.cleanup(requestId);
            cancellation.unbind();
        }
        response.put("requestId", requestId);
        response.put("executionMode", executionMode);
        response.put("totalConnections", request.getConnectionIds().size());
        response.put("successConnections", success);
        response.put("failedConnections", request.getConnectionIds().size() - success);
        response.put("cancelled", cancelledFlag);
        response.put("results", results);
        return response;
    }

    /** 取消进行中的执行请求：标记取消并跨线程中止目标库的活跃查询。幂等，可重复调用。 */
    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestBody Map<String, String> body) {
        String requestId = body == null ? null : body.get("requestId");
        if (requestId == null || requestId.trim().isEmpty()) throw new IllegalArgumentException("requestId 不能为空");
        cancellation.requestCancel(requestId.trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", requestId.trim());
        result.put("cancelled", true);
        return result;
    }

}
