package com.sxwh.sqlcontroller.model;

/** SQL 方言转换请求。 */
public class ConvertRequest {
    /** 输入 SQL，支持多条，以分号分隔。 */
    private String sql;
    /** 目标数据库类型：dm（达梦）或 oscar（神通）。 */
    private String targetDbType;

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public String getTargetDbType() {
        return targetDbType;
    }

    public void setTargetDbType(String targetDbType) {
        this.targetDbType = targetDbType;
    }
}
