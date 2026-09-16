package com.sxwh.sqlcontroller.model;

import java.time.LocalDateTime;

/** 数据库连接配置实体；内部开发平台可返回完整连接信息。 */
public class DatabaseConnection {
    private Long id;
    private String dbType;
    private String regionName;
    private String host;
    private Integer port;
    private String databaseName;
    private String jdbcUrl;
    private String username;
    private String password;
    private String driverClass;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long v) {
        id = v;
    }

    public String getDbType() {
        return dbType;
    }

    public void setDbType(String v) {
        dbType = v;
    }

    public String getRegionName() {
        return regionName;
    }

    public void setRegionName(String v) {
        regionName = v;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String v) {
        host = v;
    }

    /** 数据库服务端口。 */
    public Integer getPort() {
        return port;
    }

    public void setPort(Integer v) {
        port = v;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String v) {
        databaseName = v;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String v) {
        jdbcUrl = v;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String v) {
        username = v;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String v) {
        password = v;
    }

    public String getDriverClass() {
        return driverClass;
    }

    public void setDriverClass(String v) {
        driverClass = v;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String v) {
        remark = v;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime v) {
        createdAt = v;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime v) {
        updatedAt = v;
    }
}
