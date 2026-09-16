package com.sxwh.sqlcontroller.model;

import java.util.ArrayList;
import java.util.List;

/** SQL 编辑器联想数据源响应：表/字段清单（来自配置指定的固定连接）。 */
public class SchemaResponse {
    /** 是否可用；false 时前端降级为纯关键字联想。 */
    private boolean enabled;
    /** 联想数据源连接 id。 */
    private Long connectionId;
    /** 数据源库名。 */
    private String databaseName;
    /** 表清单。 */
    private List<TableInfo> tables = new ArrayList<>();
    /** 不可用原因（enabled=false 时有效）。 */
    private String error;

    public static SchemaResponse disabled(String message) {
        SchemaResponse r = new SchemaResponse();
        r.setEnabled(false);
        r.setError(message);
        return r;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Long getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(Long connectionId) {
        this.connectionId = connectionId;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public List<TableInfo> getTables() {
        return tables;
    }

    public void setTables(List<TableInfo> tables) {
        this.tables = tables;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
