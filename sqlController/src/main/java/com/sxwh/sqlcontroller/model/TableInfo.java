package com.sxwh.sqlcontroller.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 联想数据源中的一张表。
 *
 * <p>联想路径只填 name/columns；结构快照路径额外填 comment（表注释）与 indexes（索引/唯一约束）。
 */
public class TableInfo {
    private String name;
    private List<ColumnInfo> columns = new ArrayList<>();

    /** 表注释（null=未探测/无）。快照路径才有；联想路径恒为 null。 */
    private String comment;

    /** 索引（含唯一约束与主键索引；主键项 primary=true，生成建表时跳过）。快照路径才有。 */
    private List<IndexInfo> indexes = new ArrayList<>();

    public TableInfo() {
    }

    public TableInfo(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<ColumnInfo> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnInfo> columns) {
        this.columns = columns;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public List<IndexInfo> getIndexes() {
        return indexes;
    }

    public void setIndexes(List<IndexInfo> indexes) {
        this.indexes = indexes == null ? new ArrayList<>() : indexes;
    }
}
