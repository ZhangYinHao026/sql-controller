package com.sxwh.sqlcontroller.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构快照里的一个索引（含唯一约束）。
 *
 * <p>用途：字段对比的「缺整表建表」需要还原基准库的 UNIQUE KEY / KEY，否则生成的建表缺索引；
 * 逆向地，目标库缺索引也能生成 CREATE INDEX 补丁。
 *
 * <p>语义约定：
 * <ul>
 *   <li>{@code primary=true} 表示这是主键索引——主键已由列级 {@code primaryKey} + 表级 PRIMARY KEY 表达，
 *       下游生成建表时应跳过本项，避免重复定义。</li>
 *   <li>唯一约束/唯一索引统一按 {@code unique=true} 表达；{@code name} 优先取约束名（DM/神通的
 *       {@code uk_xxx}），无约束时取索引名。</li>
 * </ul>
 */
public class IndexInfo {

    /** 索引名或约束名（DM/神通唯一约束优先取约束名）。 */
    private String name;

    /** 索引列（按 column_position 升序）。 */
    private List<String> columns = new ArrayList<>();

    /** 是否唯一（含 UNIQUE 约束）。主键索引此项为 false（由 primary 表达）。 */
    private boolean unique;

    /** 是否主键索引（生成建表时跳过）。 */
    private boolean primary;

    /** 索引类型原文（NORMAL / CLUSTER / BTREE ...，可空）。 */
    private String type;

    public IndexInfo() {
    }

    public IndexInfo(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns == null ? new ArrayList<>() : columns;
    }

    public boolean isUnique() {
        return unique;
    }

    public void setUnique(boolean unique) {
        this.unique = unique;
    }

    public boolean isPrimary() {
        return primary;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
