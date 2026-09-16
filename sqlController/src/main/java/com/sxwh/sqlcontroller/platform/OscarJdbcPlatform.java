package com.sxwh.sqlcontroller.platform;

import com.sxwh.sqlcontroller.model.DatabaseConnection;
import org.springframework.stereotype.Component;

/** 神通 JDBC 平台：使用 ROW_NUMBER() 实现数据库端分页。 */
@Component
public class OscarJdbcPlatform extends AbstractJdbcPlatform {
    public String name() { return "oscar"; }
    public String driverClass() { return "com.oscar.Driver"; }
    public String buildJdbcUrl(DatabaseConnection c) { return "jdbc:oscar://" + c.getHost() + ":" + c.getPort() + "/" + c.getDatabaseName(); }
    public String testSql() { return "SELECT 1"; }
    public JdbcPagedSql page(String sql, int page, int pageSize) {
        // 与 DmJdbcPlatform 保持一致：显式指定常量序，避免个别神通版本对空 OVER() 同样报缺 ORDER BY。
        String wrapped = "SELECT * FROM (SELECT SQL_PAGE_INNER.*, ROW_NUMBER() OVER (ORDER BY 1) AS SQL_PAGE_ROW_NUM FROM ("
                + trimTerminalSemicolon(sql) + ") SQL_PAGE_INNER) SQL_PAGE_OUT WHERE SQL_PAGE_ROW_NUM BETWEEN ? AND ?";
        int start = (page - 1) * pageSize + 1;
        return paged(wrapped, start, start + pageSize - 1);
    }
}
