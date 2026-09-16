package com.sxwh.sqlcontroller.service;

import com.sxwh.sqlcontroller.model.*;
import com.sxwh.sqlcontroller.platform.JdbcPlatform;
import com.sxwh.sqlcontroller.platform.JdbcPlatformRegistry;
import com.sxwh.sqlcontroller.repository.*;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DatabaseConnectionService {
    private final ConnectionRepository repo;
    private final JdbcPlatformRegistry platforms;

    public DatabaseConnectionService(ConnectionRepository r, JdbcPlatformRegistry platforms) {
        repo = r;
        this.platforms = platforms;
    }

    /** 按数据库类型和关键字查询连接配置。 */
    public List<DatabaseConnection> list(String t, String k) {
        return repo.find(t, k);
    }

    /** 返回已配置的数据库类型及连接数。 */
    public List<Map<String, Object>> types() {
        return repo.typeCounts();
    }

    /** 根据主键查询连接，不存在时抛出业务异常。 */
    public DatabaseConnection get(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("连接不存在"));
    }

    /** 新增或更新连接配置，并由 JDBC 平台自动填入正确驱动类。 */
    public DatabaseConnection save(Long id, ConnectionRequest r) {
        if (r.getDbType() == null || r.getRegionName() == null || r.getHost() == null || r.getPort() == null || r.getUsername() == null || (id == null && r.getPassword() == null))
            throw new IllegalArgumentException("数据库类型、省份、主机、端口、用户名和密码不能为空");
        DatabaseConnection c = id == null ? new DatabaseConnection() : get(id);
        c.setId(id);
        c.setDbType(r.getDbType());
        c.setRegionName(r.getRegionName());
        c.setHost(r.getHost());
        c.setPort(r.getPort());
        c.setDatabaseName(r.getDatabaseName());
        c.setJdbcUrl(r.getJdbcUrl());
        c.setUsername(r.getUsername());
        if (r.getPassword() != null && !r.getPassword().trim().isEmpty()) c.setPassword(r.getPassword());
        c.setDriverClass(platform(r.getDbType()).driverClass());
        c.setRemark(r.getRemark());
        return repo.save(c);
    }

    /** 删除指定连接配置。 */
    public void delete(Long id) {
        repo.delete(id);
    }

    /** 根据数据库类型取得 JDBC 平台，并统一完成可用性校验。 */
    public JdbcPlatform platform(String dbType) { return platforms.get(dbType); }

    /** 兼容原控制器调用；实际驱动配置由 JDBC 平台策略维护。 */
    public String driver(String dbType) { return platform(dbType).driverClass(); }
}
