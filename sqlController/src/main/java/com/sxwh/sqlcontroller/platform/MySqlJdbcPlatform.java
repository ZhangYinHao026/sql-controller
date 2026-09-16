package com.sxwh.sqlcontroller.platform;

import com.sxwh.sqlcontroller.model.DatabaseConnection;
import org.springframework.stereotype.Component;

/** MySQL JDBC 平台：使用 LIMIT / OFFSET 分页。 */
@Component
public class MySqlJdbcPlatform extends AbstractJdbcPlatform {
    public String name() { return "mysql"; }
    public String driverClass() { return "com.mysql.cj.jdbc.Driver"; }
    public String buildJdbcUrl(DatabaseConnection c) {
        // 多语句执行已由执行服务在应用层统一拆分逐条执行，不再依赖驱动级 allowMultiQueries；
        // 该参数仍保留在默认 URL 中，兼容其它直连该 URL 发送多语句的工具/场景。
        return "jdbc:mysql://" + c.getHost() + ":" + c.getPort() + "/" + c.getDatabaseName()
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowMultiQueries=true";
    }
    public String testSql() { return "SELECT 1"; }
    public JdbcPagedSql page(String sql, int page, int pageSize) {
        return paged(trimTerminalSemicolon(sql) + " LIMIT ? OFFSET ?", pageSize, (page - 1) * pageSize);
    }
}
