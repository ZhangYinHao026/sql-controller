package com.sxwh.sqlcontroller.model;

/**
 * 一张表的字段信息。
 *
 * <p>联想路径只填 name/type；字段对比快照路径额外填 nullable/primaryKey/autoIncrement/comment。
 * 布尔字段用 Boolean（null=未知/未探测），避免把"探测失败"误当成"false"。
 */
public class ColumnInfo {
    private String name;
    private String type;
    /** 是否允许 NULL（null=未探测）。 */
    private Boolean nullable;
    /** 是否主键列（null=未探测）。 */
    private Boolean primaryKey;
    /** 是否自增列（null=未探测）。 */
    private Boolean autoIncrement;
    /** 列注释（null=未探测/无）。 */
    private String comment;

    public ColumnInfo() {
    }

    public ColumnInfo(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Boolean getNullable() {
        return nullable;
    }

    public void setNullable(Boolean nullable) {
        this.nullable = nullable;
    }

    public Boolean getPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(Boolean primaryKey) {
        this.primaryKey = primaryKey;
    }

    public Boolean getAutoIncrement() {
        return autoIncrement;
    }

    public void setAutoIncrement(Boolean autoIncrement) {
        this.autoIncrement = autoIncrement;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
