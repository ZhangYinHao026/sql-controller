package com.sxwh.sqlcontroller.model;

import java.util.List;
import java.util.Map;

/** SQL 执行请求对象，支持同一数据库类型的多个连接。 */
public class ExecuteRequest {
    private List<Long> connectionIds;
    private String sql, executionMode, requestId;
    private Integer page, pageSize;
    private Boolean confirmed;
    /** 是否以单事务执行多语句脚本：true 时整段脚本成功统一提交、失败统一回滚（纯 DML 生效，DDL 仍按数据库规则隐式提交）。 */
    private Boolean transactional;
    private Long scriptNodeId;
    private Map<String, Object> params;

    public List<Long> getConnectionIds() {
        return connectionIds;
    }

    public void setConnectionIds(List<Long> v) {
        connectionIds = v;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String v) {
        sql = v;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String v) {
        executionMode = v;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String v) {
        requestId = v;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer v) {
        page = v;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer v) {
        pageSize = v;
    }

    public Boolean getConfirmed() {
        return confirmed;
    }

    public void setConfirmed(Boolean v) {
        confirmed = v;
    }

    public Boolean getTransactional() { return transactional; }
    public void setTransactional(Boolean transactional) { this.transactional = transactional; }

    /** 由侧边栏脚本执行时传入的脚本节点 ID。 */
    public Long getScriptNodeId() { return scriptNodeId; }
    public void setScriptNodeId(Long scriptNodeId) { this.scriptNodeId = scriptNodeId; }
    /** SQL 脚本 #{参数名}、${参数名} 占位符对应的参数值。 */
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
}
