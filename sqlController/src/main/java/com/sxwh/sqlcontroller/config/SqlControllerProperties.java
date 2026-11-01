package com.sxwh.sqlcontroller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** SQL 执行相关配置，统一从 application.yml 的 sql-controller 节点读取。 */
@Component
@ConfigurationProperties(prefix = "sql-controller")
public class SqlControllerProperties {
    /** 查询类 SQL（SELECT/SHOW/DESC/WITH 等）的 JDBC 超时，单位秒；亦是未分类语句的默认超时。 */
    private int executionTimeoutSeconds = 300;
    /** 写类 SQL（INSERT/UPDATE/DELETE/MERGE 等 DML）的 JDBC 超时，单位秒。 */
    private int dmlTimeoutSeconds = 300;
    /** 结构类 SQL（CREATE/ALTER/DROP/TRUNCATE 等 DDL，如大表 ADD COLUMN）的 JDBC 超时，单位秒。 */
    private int ddlTimeoutSeconds = 600;
    /** 连接测试的超时，单位秒。 */
    private int connectionTestTimeoutSeconds = 10;
    /** 单次查询允许返回的最大页大小。 */
    private int maxPageSize = 100;
    /** 未传分页参数时使用的默认页大小。 */
    private int defaultPageSize = 10;
    /** 表浏览写入口开关（行级增删改 / 执行 DDL）。 */
    private Write write = new Write();

    /** 写入口配置：默认全关——本工具没有登录鉴权，默认不允许「点一下就改库」。 */
    public static class Write {
        /** 本地直连库：单元格编辑 / 提交变更 / 执行 DDL。 */
        private boolean enabled = false;
        /** 线上（生产）区域：同一套写入口是否可用。 */
        private boolean onlineEnabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isOnlineEnabled() { return onlineEnabled; }
        public void setOnlineEnabled(boolean onlineEnabled) { this.onlineEnabled = onlineEnabled; }
    }

    public Write getWrite() { return write; }
    public void setWrite(Write write) { this.write = write; }

    public int getExecutionTimeoutSeconds() { return executionTimeoutSeconds; }
    public void setExecutionTimeoutSeconds(int executionTimeoutSeconds) { this.executionTimeoutSeconds = executionTimeoutSeconds; }
    public int getDmlTimeoutSeconds() { return dmlTimeoutSeconds; }
    public void setDmlTimeoutSeconds(int dmlTimeoutSeconds) { this.dmlTimeoutSeconds = dmlTimeoutSeconds; }
    public int getDdlTimeoutSeconds() { return ddlTimeoutSeconds; }
    public void setDdlTimeoutSeconds(int ddlTimeoutSeconds) { this.ddlTimeoutSeconds = ddlTimeoutSeconds; }
    public int getConnectionTestTimeoutSeconds() { return connectionTestTimeoutSeconds; }
    public void setConnectionTestTimeoutSeconds(int connectionTestTimeoutSeconds) { this.connectionTestTimeoutSeconds = connectionTestTimeoutSeconds; }
    public int getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(int maxPageSize) { this.maxPageSize = maxPageSize; }
    public int getDefaultPageSize() { return defaultPageSize; }
    public void setDefaultPageSize(int defaultPageSize) { this.defaultPageSize = defaultPageSize; }

    /** 返回经边界保护后的查询/默认执行超时。 */
    public int executionTimeout() { return Math.max(1, executionTimeoutSeconds); }
    /** 返回经边界保护后的 DML 超时。 */
    public int dmlTimeout() { return Math.max(1, dmlTimeoutSeconds); }
    /** 返回经边界保护后的 DDL 超时。 */
    public int ddlTimeout() { return Math.max(1, ddlTimeoutSeconds); }
    /** 返回经边界保护后的连接测试超时。 */
    public int connectionTestTimeout() { return Math.max(1, connectionTestTimeoutSeconds); }
    /** 将外部传入的页大小限制在服务端上限内。 */
    public int normalizePageSize(Integer value) {
        int defaultSize = Math.max(1, defaultPageSize);
        int size = value == null ? defaultSize : value;
        return Math.min(Math.max(1, size), Math.max(1, maxPageSize));
    }
}
