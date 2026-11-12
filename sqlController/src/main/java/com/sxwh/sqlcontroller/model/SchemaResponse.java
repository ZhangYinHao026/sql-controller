package com.sxwh.sqlcontroller.model;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 编辑器联想数据源响应：表/字段名清单（来自配置指定的固定连接）。
 *
 * <p>只带名字：编辑器只用到表名和列名，完整元数据（类型/主键/注释）由表浏览与快照接口提供，
 * 带上会让响应大一倍以上。
 */
public class SchemaResponse {
    /** 是否可用；false 时前端降级为纯关键字联想。 */
    private boolean enabled;
    /** 联想数据源连接 id。 */
    private Long connectionId;
    /** 数据源库名。 */
    private String databaseName;
    /** 表清单。 */
    private List<Table> tables = new ArrayList<>();
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

    public List<Table> getTables() {
        return tables;
    }

    public void setTables(List<Table> tables) {
        this.tables = tables;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    /** 一张表：只有表名和列名。 */
    public static class Table {
        private String name;
        private List<Column> columns = new ArrayList<>();

        public Table() {
        }

        public Table(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<Column> getColumns() {
            return columns;
        }

        public void setColumns(List<Column> columns) {
            this.columns = columns;
        }
    }

    /** 一列：只有列名。 */
    public static class Column {
        private String name;

        public Column() {
        }

        public Column(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
