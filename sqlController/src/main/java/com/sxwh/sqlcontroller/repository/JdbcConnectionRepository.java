package com.sxwh.sqlcontroller.repository;

import com.sxwh.sqlcontroller.model.DatabaseConnection;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.util.*;

@Repository
public class JdbcConnectionRepository implements ConnectionRepository {
    private final JdbcTemplate jdbc;

    public JdbcConnectionRepository(JdbcTemplate j) {
        jdbc = j;
    }

    private final RowMapper<DatabaseConnection> map = (r, n) -> {
        DatabaseConnection c = new DatabaseConnection();
        c.setId(r.getLong("id"));
        c.setDbType(r.getString("db_type"));
        c.setRegionName(r.getString("region_name"));
        c.setHost(r.getString("host"));
        c.setPort(r.getInt("port"));
        c.setDatabaseName(r.getString("database_name"));
        c.setJdbcUrl(r.getString("jdbc_url"));
        c.setUsername(r.getString("username"));
        c.setPassword(r.getString("password_encrypted"));
        c.setDriverClass(r.getString("driver_class"));
        c.setRemark(r.getString("remark"));
        return c;
    };

    public List<DatabaseConnection> find(String type, String key) {
        String q = "select * from database_connection where (? is null or db_type=?) and (? is null or region_name like ? or host like ?) order by id asc";
        String k = key == null || key.trim().isEmpty() ? null : "%" + key.trim() + "%";
        return jdbc.query(q, map, type, type, k, k, k);
    }

    public Optional<DatabaseConnection> findById(Long id) {
        List<DatabaseConnection> x = jdbc.query("select * from database_connection where id=?", map, id);
        return x.stream().findFirst();
    }

    public DatabaseConnection save(DatabaseConnection c) {
        if (c.getId() == null) {
            KeyHolder h = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement p = con.prepareStatement("insert into database_connection(db_type,region_name,host,port,database_name,jdbc_url,username,password_encrypted,driver_class,remark) values(?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                bind(p, c);
                return p;
            }, h);
            Number generatedId = h.getKey();
            if (generatedId == null) {
                throw new IllegalStateException("新增连接配置失败：数据库未返回自增主键");
            }
            c.setId(generatedId.longValue());
        } else
            jdbc.update("update database_connection set db_type=?,region_name=?,host=?,port=?,database_name=?,jdbc_url=?,username=?,password_encrypted=?,driver_class=?,remark=? where id=?", p -> {
                bind(p, c);
                p.setLong(11, c.getId());
            });
        return c;
    }

    private void bind(PreparedStatement p, DatabaseConnection c) throws SQLException {
        p.setString(1, c.getDbType());
        p.setString(2, c.getRegionName());
        p.setString(3, c.getHost());
        p.setInt(4, c.getPort());
        p.setString(5, c.getDatabaseName());
        p.setString(6, c.getJdbcUrl());
        p.setString(7, c.getUsername());
        p.setString(8, c.getPassword());
        p.setString(9, c.getDriverClass());
        p.setString(10, c.getRemark());
    }

    public void delete(Long id) {
        jdbc.update("delete from database_connection where id=?", id);
    }

    public List<Map<String, Object>> typeCounts() {
        return jdbc.queryForList("select db_type as dbType,count(*) as total from database_connection group by db_type order by db_type");
    }
}
