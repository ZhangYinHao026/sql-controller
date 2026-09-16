package com.sxwh.sqlcontroller.platform;

import java.util.Arrays;
import java.util.List;

/** JDBC 平台公共实现，提供 SQL 清理和通用 COUNT 包装。 */
public abstract class AbstractJdbcPlatform implements JdbcPlatform {
    /** 去掉末尾分号，避免嵌套查询或追加分页时出现语法错误。 */
    protected String trimTerminalSemicolon(String sql) {
        String value = sql == null ? "" : sql.trim();
        while (value.endsWith(";")) value = value.substring(0, value.length() - 1).trim();
        return value;
    }
    /** 使用子查询统计原查询总行数；调用方仅对 SELECT/WITH 使用。 */
    @Override
    public String count(String sql) { return "SELECT COUNT(*) FROM (" + trimTerminalSemicolon(sql) + ") SQL_CONTROLLER_COUNT"; }
    /** 创建分页 SQL 返回对象。 */
    protected JdbcPagedSql paged(String sql, Object... parameters) { return new JdbcPagedSql(sql, Arrays.asList(parameters)); }
}
