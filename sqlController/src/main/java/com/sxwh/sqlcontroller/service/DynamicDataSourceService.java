package com.sxwh.sqlcontroller.service;

import com.sxwh.sqlcontroller.config.SqlControllerProperties;
import com.sxwh.sqlcontroller.model.DatabaseConnection;
import com.sxwh.sqlcontroller.platform.JdbcPagedSql;
import com.sxwh.sqlcontroller.platform.JdbcPlatform;
import com.sxwh.sqlcontroller.platform.JdbcPlatformRegistry;
import com.sxwh.sqlcontroller.util.SqlScriptSplitter;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.Reader;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLXML;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 动态连接执行服务：JDBC 连接、参数绑定、数据库端分页和结果序列化。 */
@Service
public class DynamicDataSourceService {
    private final JdbcPlatformRegistry platforms;
    private final SqlControllerProperties properties;
    private final SqlExecutionCancellation cancellation;

    public DynamicDataSourceService(JdbcPlatformRegistry platforms, SqlControllerProperties properties,
                                    SqlExecutionCancellation cancellation) {
        this.platforms = platforms;
        this.properties = properties;
        this.cancellation = cancellation;
    }

    /** 测试连接，使用平台提供的轻量 SQL 和配置的超时。 */
    public Map<String, Object> testConnection(DatabaseConnection connection) {
        long started = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            JdbcPlatform platform = platforms.get(connection.getDbType());
            Class.forName(resolveDriver(connection, platform));
            DriverManager.setLoginTimeout(properties.connectionTestTimeout());
            try (Connection jdbcConnection = DriverManager.getConnection(resolveUrl(connection, platform), connection.getUsername(), connection.getPassword());
                 Statement statement = jdbcConnection.createStatement()) {
                statement.setQueryTimeout(properties.connectionTestTimeout());
                statement.execute(platform.testSql());
                result.put("success", true);
            }
        } catch (Exception exception) {
            result.put("success", false);
            result.put("errorMessage", exception.getClass().getSimpleName() + ": " + exception.getMessage());
        } finally {
            result.put("durationMs", System.currentTimeMillis() - started);
        }
        return result;
    }

    /** 执行无业务参数 SQL，供普通编辑器调用。 */
    public Map<String, Object> execute(DatabaseConnection connection, String sql, int page, int pageSize) throws Exception {
        return execute(connection, sql, Collections.emptyList(), page, pageSize);
    }

    /**
     * 不分页、不归一化 pageSize，直接执行 SQL。供 SchemaService 等后台元数据查询使用 —
     * 避开 {@link com.sxwh.sqlcontroller.config.SqlControllerProperties#getMaxPageSize()} 对分页结果的钳制
     * （元数据查询不应受用户层 pageSize 限制）。
     */
    public Map<String, Object> executeRaw(DatabaseConnection connection, String sql, List<Object> parameters) throws Exception {
        JdbcPlatform platform = platforms.get(connection.getDbType());
        Class.forName(resolveDriver(connection, platform));
        long started = System.currentTimeMillis();
        try (Connection jdbcConnection = DriverManager.getConnection(resolveUrl(connection, platform), connection.getUsername(), connection.getPassword());
             PreparedStatement statement = jdbcConnection.prepareStatement(sql)) {
            statement.setQueryTimeout(properties.executionTimeout());
            bind(statement, parameters == null ? Collections.emptyList() : parameters);
            Map<String, Object> result = baseResult(1, 0, started);
            if (statement.execute()) {
                try (ResultSet resultSet = statement.getResultSet()) {
                    readResultSet(resultSet, result);
                }
                result.put("total", (long) ((List<?>) result.get("rows")).size());
            } else {
                result.put("total", 0L);
                result.put("affectedRows", Math.max(0, statement.getUpdateCount()));
            }
            result.put("durationMs", System.currentTimeMillis() - started);
            return result;
        }
    }

    /**
     * 执行 SQL。SELECT/WITH 将分页与统计下推给平台，其他语句不包装分页。
     * 业务参数和分页参数均通过 PreparedStatement 绑定。
     * 默认非单事务（每条语句各自自动提交），兼容历史调用。
     */
    public Map<String, Object> execute(DatabaseConnection connection, String sql, List<Object> parameters, int page, int pageSize) throws Exception {
        return execute(connection, sql, parameters, page, pageSize, false);
    }

    /**
     * 执行 SQL（transactional=true 时整段脚本单事务：全部成功统一提交、任一条失败统一回滚）。
     * 注意：脚本中的 DDL（CREATE/ALTER/DROP/TRUNCATE 等）在 MySQL/DM8/OSCAR 都会隐式提交，
     * 单事务仅能保证纯 DML 部分整体生效或整体回滚；含 DDL 的脚本走到 DDL 处即被数据库强制落库。
     */
    public Map<String, Object> execute(DatabaseConnection connection, String sql, List<Object> parameters, int page, int pageSize,
                                       boolean transactional) throws Exception {
        JdbcPlatform platform = platforms.get(connection.getDbType());
        Class.forName(resolveDriver(connection, platform));
        int normalizedPage = Math.max(1, page);
        int normalizedSize = properties.normalizePageSize(pageSize);

        // 多语句脚本统一由应用层按语句边界拆分后逐条执行，不依赖 JDBC 驱动级支持：
        // MySQL 驱动虽支持 allowMultiQueries，但需连接 URL 带该参数——而连接表里保存的自定义 URL 常缺失此参数，
        // 导致驱动把整段多语句当单条解析而报语法错（历史连接从未真正启用驱动级多语句）；
        // 达梦/神通等平台则根本没有驱动级多语句支持。统一拆分可保证三类库行为一致、错误可定位到第几条。
        List<String> script = SqlScriptSplitter.split(sql);
        if (script.size() > 1) {
            if (parameters != null && !parameters.isEmpty()) {
                throw new IllegalArgumentException("带预编译参数(#{})的脚本暂不支持一次执行多条语句：请将脚本拆分为单条后再执行");
            }
            return executeScript(connection, platform, script, normalizedPage, normalizedSize, transactional);
        }
        // 仅一条时使用拆分后的干净文本（去首尾空白/多余尾分号），语义不变，兼容达梦对尾分号的容忍差异。
        if (script.size() == 1) sql = script.get(0);

        boolean pageable = isPageableQuery(sql);
        JdbcPagedSql paged = pageable ? platform.page(sql, normalizedPage, normalizedSize) : new JdbcPagedSql(sql, Collections.emptyList());
        List<Object> values = new ArrayList<>(parameters == null ? Collections.emptyList() : parameters);
        values.addAll(paged.getParameters());

        long started = System.currentTimeMillis();
        try (Connection jdbcConnection = DriverManager.getConnection(resolveUrl(connection, platform), connection.getUsername(), connection.getPassword());
             PreparedStatement statement = jdbcConnection.prepareStatement(paged.getSql())) {
            // 按语句类型取超时：查询 30s / DML 300s / DDL 600s（大表 ALTER 需长超时，见 timeoutFor）
            statement.setQueryTimeout(timeoutFor(sql));
            cancellation.track(statement);
            try {
                // 单事务模式：关闭自动提交，成功统一提交、失败统一回滚（多语句脚本在 executeScript 内自管事务，不走到这里）。
                if (transactional) jdbcConnection.setAutoCommit(false);
                try {
                    bind(statement, values);
                    boolean hasResultSet = statement.execute();
                    Map<String, Object> result = baseResult(normalizedPage, normalizedSize, started);
                    if (hasResultSet) {
                        try (ResultSet resultSet = statement.getResultSet()) {
                            readResultSet(resultSet, result);
                        }
                        result.put("total", pageable ? count(connection, platform, sql, parameters) : ((List<?>) result.get("rows")).size());
                    } else {
                        // 非查询语句：取影响行数（多语句已拆分逐条执行，此处单条即可，循环兼容驱动返回多个 update count 的场景）。
                        long affected = 0;
                        while (true) {
                            int count = statement.getUpdateCount();
                            if (count == -1) break;
                            affected += Math.max(0, count);
                            while (statement.getMoreResults()) { /* 跳过结果集，继续前进 */ }
                        }
                        result.put("total", 0L);
                        result.put("affectedRows", affected);
                    }
                    result.put("durationMs", System.currentTimeMillis() - started);
                    if (transactional) {
                        // 提交前消费掉驱动侧可能残留的后续结果（如 SELECT 开头的多语句批次），避免“结果集未关闭”导致提交失败。
                        drainRemaining(statement);
                        jdbcConnection.commit();
                    }
                    return result;
                } catch (Exception exception) {
                    if (transactional) rollbackQuietly(jdbcConnection);
                    throw exception;
                }
            } finally {
                cancellation.untrack();
            }
        }
    }

    /**
     * 逐条顺序执行拆分后的多语句脚本（所有平台统一路径）。
     * 非查询语句的影响行数累加；查询语句的结果集按执行顺序依次读取，
     * 最后一个查询的结果作为最终展示（前面的查询结果仅消费掉以推进后续语句）。
     * transactional=false：任一条失败立即中止，此前语句各自已提交、无法整体回滚；
     * transactional=true：整段包成一个事务，任一条失败统一回滚（脚本中的 DDL 仍按数据库规则隐式提交）。
     */
    private Map<String, Object> executeScript(DatabaseConnection connection, JdbcPlatform platform,
                                              List<String> statements, int page, int pageSize,
                                              boolean transactional) throws Exception {
        long started = System.currentTimeMillis();
        Map<String, Object> result = baseResult(page, pageSize, started);
        long affected = 0L;
        boolean hasQuery = false;
        try (Connection jdbcConnection = DriverManager.getConnection(resolveUrl(connection, platform), connection.getUsername(), connection.getPassword());
             Statement statement = jdbcConnection.createStatement()) {
            // 单事务整段取最宽松的 DDL 超时（一次事务中途不换档，避免语义混乱）；
            // 非事务多语句脚本在循环内逐条按各自语句类型设超时。
            statement.setQueryTimeout(transactional ? properties.ddlTimeout() : properties.executionTimeout());
            cancellation.track(statement);
            try {
                if (transactional) jdbcConnection.setAutoCommit(false);
                for (int position = 0; position < statements.size(); position++) {
                    String single = statements.get(position).trim();
                    while (single.endsWith(";")) single = single.substring(0, single.length() - 1).trim();
                    if (!transactional) statement.setQueryTimeout(timeoutFor(single));
                    try {
                        if (statement.execute(single)) {
                            try (ResultSet resultSet = statement.getResultSet()) {
                                readResultSet(resultSet, result);
                            }
                            hasQuery = true;
                        } else {
                            affected += Math.max(0, statement.getUpdateCount());
                        }
                    } catch (Exception exception) {
                        String detail = exception.getMessage() == null ? exception.toString() : exception.getMessage();
                        if (transactional) {
                            rollbackQuietly(jdbcConnection);
                            throw new IllegalStateException("第 " + (position + 1) + "/" + statements.size()
                                    + " 条语句执行失败，已回滚本次单事务（脚本中含 DDL 的部分已按数据库规则隐式提交，无法回滚）: " + detail, exception);
                        }
                        throw new IllegalStateException("第 " + (position + 1) + "/" + statements.size()
                                + " 条语句执行失败（此前 " + position + " 条已执行成功且各自已提交）: " + detail, exception);
                    }
                }
                if (transactional) jdbcConnection.commit();
            } finally {
                cancellation.untrack();
            }
        }
        result.put("total", hasQuery ? (long) ((List<?>) result.get("rows")).size() : 0L);
        result.put("affectedRows", affected);
        result.put("durationMs", System.currentTimeMillis() - started);
        return result;
    }

    /** 消费驱动多语句批次中尚未读取的剩余结果，保证提交/关闭连接前无未处理结果集。 */
    private void drainRemaining(Statement statement) throws Exception {
        while (statement.getMoreResults() || statement.getUpdateCount() != -1) {
            try (ResultSet pending = statement.getResultSet()) { /* 丢弃 */ }
        }
    }

    /** DDL（结构变更，可能触发整表重建/长锁，需要最宽松超时）。 */
    private static final Set<String> DDL_HEAD = new HashSet<>(Arrays.asList(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME"));

    /** DML（数据变更，批量改数万行也常见，给中等超时）。 */
    private static final Set<String> DML_HEAD = new HashSet<>(Arrays.asList(
            "INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE"));

    /**
     * 按 SQL 首关键字返回应使用的 JDBC 超时（秒）：
     * DDL → ddlTimeout（默认 600s），DML → dmlTimeout（默认 300s），其余（查询/未分类）→ executionTimeout（默认 30s）。
     * 解决大表 ALTER TABLE ADD COLUMN 等慢 DDL 被统一 30s 超时误杀的问题。
     */
    private int timeoutFor(String sql) {
        if (sql == null || sql.trim().isEmpty()) return properties.executionTimeout();
        String head = sql.trim().split("\\s+")[0].toUpperCase(Locale.ROOT);
        if (DDL_HEAD.contains(head)) return properties.ddlTimeout();
        if (DML_HEAD.contains(head)) return properties.dmlTimeout();
        return properties.executionTimeout();
    }

    /** 单事务失败时静默回滚当前连接上未提交的改动（回滚异常不覆盖原始错误）。 */
    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (Exception ignored) {
            // 忽略：原始执行异常优先向上抛
        }
    }

    /** 对 SELECT/WITH 单独执行 COUNT，避免将全部结果读入 JVM 内存。 */
    private long count(DatabaseConnection connection, JdbcPlatform platform, String sql, List<Object> parameters) throws Exception {
        try (Connection jdbcConnection = DriverManager.getConnection(resolveUrl(connection, platform), connection.getUsername(), connection.getPassword());
             PreparedStatement statement = jdbcConnection.prepareStatement(platform.count(sql))) {
            statement.setQueryTimeout(properties.executionTimeout());
            cancellation.track(statement);
            try {
                bind(statement, parameters == null ? Collections.emptyList() : parameters);
                try (ResultSet resultSet = statement.executeQuery()) { return resultSet.next() ? resultSet.getLong(1) : 0L; }
            } finally {
                cancellation.untrack();
            }
        }
    }

    /** 按顺序将参数绑定到 PreparedStatement。 */
    private void bind(PreparedStatement statement, List<Object> parameters) throws Exception {
        for (int index = 0; index < parameters.size(); index++) statement.setObject(index + 1, parameters.get(index));
    }

    /** 创建统一返回结构。 */
    private Map<String, Object> baseResult(int page, int pageSize, long started) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", new ArrayList<String>());
        result.put("rows", new ArrayList<Map<String, Object>>());
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("durationMs", System.currentTimeMillis() - started);
        result.put("affectedRows", 0L);
        return result;
    }

    /** 将当前页 ResultSet 转成可 JSON 序列化的列和行。 */
    private void readResultSet(ResultSet resultSet, Map<String, Object> result) throws Exception {
        ResultSetMetaData metadata = resultSet.getMetaData();
        List<String> columns = new ArrayList<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) columns.add(metadata.getColumnLabel(index));
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) row.put(columns.get(index - 1), normalizeValue(resultSet.getObject(index)));
            rows.add(row);
        }
        result.put("columns", columns);
        result.put("rows", rows);
    }

    /** 仅 SELECT/WITH 使用方言分页，SHOW/DESC 等语句保持原生语法。 */
    private boolean isPageableQuery(String sql) {
        String value = sql == null ? "" : sql.trim().toUpperCase(Locale.ROOT);
        return value.startsWith("SELECT") || value.startsWith("WITH");
    }

    /** 自定义 JDBC URL 优先；未设置时由 JDBC 平台构建。 */
    private String resolveUrl(DatabaseConnection connection, JdbcPlatform platform) {
        return connection.getJdbcUrl() == null || connection.getJdbcUrl().trim().isEmpty() ? platform.buildJdbcUrl(connection) : connection.getJdbcUrl().trim();
    }

    /** 平台驱动为主，保留连接表中旧驱动配置作为兼容兜底。 */
    private String resolveDriver(DatabaseConnection connection, JdbcPlatform platform) {
        return platform.driverClass() == null || platform.driverClass().trim().isEmpty() ? connection.getDriverClass() : platform.driverClass();
    }

    /** 将 JDBC 专有对象转换成 Jackson 可序列化类型。 */
    private Object normalizeValue(Object value) throws Exception {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character || value instanceof TemporalAccessor) return value;
        if (value instanceof java.util.Date) return value;
        if (value instanceof byte[]) return blobLabel(((byte[]) value).length);
        if (value instanceof Blob) return blobLabel(((Blob) value).length());
        if (value instanceof Clob) return readClob((Clob) value);
        if (value instanceof SQLXML) return "[SQLXML]";
        if (value instanceof InputStream) return "[Binary stream]";
        if (value instanceof Reader) return "[Character stream]";
        if (value instanceof Array) return "[SQL Array]";
        if (value instanceof RowId) return value.toString();
        return String.valueOf(value);
    }

    /** 返回 BLOB 大小提示，避免直接输出二进制数据。 */
    private String blobLabel(long bytes) {
        if (bytes < 1024) return "[BLOB " + bytes + " B]";
        if (bytes < 1024 * 1024) return "[BLOB " + String.format(Locale.ROOT, "%.1f", bytes / 1024.0) + " KB]";
        return "[BLOB " + String.format(Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0) + " MB]";
    }

    /** 限制 CLOB 返回文本长度，避免响应数据过大。 */
    private String readClob(Clob clob) throws Exception {
        final int limit = 2000;
        long length = clob.length();
        String text = clob.getSubString(1, (int) Math.min(length, limit));
        return length > limit ? text + "（已截断）" : text;
    }
}
