package com.sxwh.sqlcontroller.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个连接的库表结构快照（字段对比用）。
 *
 * <p>与联想用的 {@link SchemaResponse} 不同：本对象不绑定"联想数据源"配置，
 * 前端可对任意已保存连接发起对比快照；ok=false 时 error 说明失败原因，
 * 其余比对库仍可继续，不互相拖累。
 */
public class SchemaSnapshot {
    private Long connectionId;
    private String dbType;
    private String databaseName;
    /** 抓取时间（epoch ms），前端可据此提示快照陈旧。 */
    private long fetchedAt;
    private boolean ok;
    private String error;
    private List<TableInfo> tables = new ArrayList<>();

    public Long getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(Long connectionId) {
        this.connectionId = connectionId;
    }

    public String getDbType() {
        return dbType;
    }

    public void setDbType(String dbType) {
        this.dbType = dbType;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public long getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(long fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public List<TableInfo> getTables() {
        return tables;
    }

    public void setTables(List<TableInfo> tables) {
        this.tables = tables;
    }
}
