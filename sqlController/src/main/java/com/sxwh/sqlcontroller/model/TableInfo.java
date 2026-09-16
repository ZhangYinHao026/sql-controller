package com.sxwh.sqlcontroller.model;

import java.util.ArrayList;
import java.util.List;

/** 联想数据源中的一张表。 */
public class TableInfo {
    private String name;
    private List<ColumnInfo> columns = new ArrayList<>();

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
}
