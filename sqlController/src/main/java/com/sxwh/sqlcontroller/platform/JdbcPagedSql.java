package com.sxwh.sqlcontroller.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 方言分页后的 SQL 及其附加 JDBC 参数。 */
public class JdbcPagedSql {
    private final String sql;
    private final List<Object> parameters;

    public JdbcPagedSql(String sql, List<Object> parameters) {
        this.sql = sql;
        this.parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
    }
    public String getSql() { return sql; }
    public List<Object> getParameters() { return parameters; }
}
