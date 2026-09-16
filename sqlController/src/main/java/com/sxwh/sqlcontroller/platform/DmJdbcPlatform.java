package com.sxwh.sqlcontroller.platform;

import com.sxwh.sqlcontroller.model.DatabaseConnection;
import org.springframework.stereotype.Component;

/** 达梦 JDBC 平台：使用 ROW_NUMBER() 实现跨版本稳定分页。 */
@Component
public class DmJdbcPlatform extends AbstractJdbcPlatform {
    public String name() { return "dm"; }
    public String driverClass() { return "dm.jdbc.driver.DmDriver"; }
    public String buildJdbcUrl(DatabaseConnection c) {
        return "jdbc:dm://" + c.getHost() + ":" + c.getPort() + "/" + c.getDatabaseName()
                + "?serverTimezone=UTC&useSSL=false&useUnicode=true&characterEncoding=utf-8&clobAsString=true&columnNameUpperCase=false";
    }
    public String testSql() { return "SELECT 1"; }
    public JdbcPagedSql page(String sql, int page, int pageSize) {
        // DM8 要求 ROW_NUMBER() 的 OVER 子句必须显式指定 ORDER BY，否则报"OVER 语句中缺失 ORDER BY 子句"。
        // ORDER BY 1 为常量序（任意稳定序），仅用于生成行号做分页，与 OVER () 语义等价。
        String wrapped = "SELECT * FROM (SELECT SQL_PAGE_INNER.*, ROW_NUMBER() OVER (ORDER BY 1) AS SQL_PAGE_ROW_NUM FROM ("
                + trimTerminalSemicolon(sql) + ") SQL_PAGE_INNER) SQL_PAGE_OUT WHERE SQL_PAGE_ROW_NUM BETWEEN ? AND ?";
        int start = (page - 1) * pageSize + 1;
        return paged(wrapped, start, start + pageSize - 1);
    }
}
