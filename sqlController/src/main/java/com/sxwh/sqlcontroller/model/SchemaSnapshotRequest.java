package com.sxwh.sqlcontroller.model;

import java.util.List;

/** 字段对比快照请求：一次抓取基准库 + 多个比对库的结构。 */
public class SchemaSnapshotRequest {
    private List<Long> connectionIds;
    /** true=跳过缓存强制重抓（连接/结构变更后用）。 */
    private boolean force;
    /** 可选：仅抓这些表（执行补丁后局部刷新用）。非空时跳过整库缓存、只查列出的表，也不写缓存。 */
    private List<String> tables;

    public List<Long> getConnectionIds() {
        return connectionIds;
    }

    public void setConnectionIds(List<Long> connectionIds) {
        this.connectionIds = connectionIds;
    }

    public boolean isForce() {
        return force;
    }

    public void setForce(boolean force) {
        this.force = force;
    }

    public List<String> getTables() {
        return tables;
    }

    public void setTables(List<String> tables) {
        this.tables = tables;
    }
}
