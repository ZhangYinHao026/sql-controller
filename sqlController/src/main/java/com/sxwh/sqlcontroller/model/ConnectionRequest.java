package com.sxwh.sqlcontroller.model;

/** 新增或编辑数据库连接的请求对象，包含主机和端口信息。 */
public class ConnectionRequest {
    private String dbType, regionName, host, databaseName, jdbcUrl, username, password, remark;
    private Integer port;

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

    public String getRemark() {
        return remark;
    }

    public void setRemark(String v) {
        remark = v;
    }
}
