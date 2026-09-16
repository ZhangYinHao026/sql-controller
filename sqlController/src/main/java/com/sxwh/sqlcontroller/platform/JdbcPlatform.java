package com.sxwh.sqlcontroller.platform;

import com.sxwh.sqlcontroller.model.DatabaseConnection;

/**
 * 数据库 JDBC 执行平台策略。
 * 负责驱动、默认连接 URL、连通性 SQL 和查询分页；不包含 SQL 转换逻辑。
 */
public interface JdbcPlatform {
    /** 平台名称，必须与 database_connection.db_type 保持一致。 */
    String name();
    /** 当前平台 JDBC 驱动类。 */
    String driverClass();
    /** 在未保存自定义 JDBC URL 时构建默认 URL。 */
    String buildJdbcUrl(DatabaseConnection connection);
    /** 用于连接测试的轻量 SQL。 */
    String testSql();
    /** 为 SELECT/WITH 查询生成数据库端分页 SQL。 */
    JdbcPagedSql page(String sql, int page, int pageSize);
    /** 生成统计总记录数的 SQL。 */
    String count(String sql);
}
