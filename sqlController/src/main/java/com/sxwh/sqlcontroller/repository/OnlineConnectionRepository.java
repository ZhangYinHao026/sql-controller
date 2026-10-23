package com.sxwh.sqlcontroller.repository;

import com.sxwh.sqlcontroller.model.OnlineConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 线上环境配置仓储（JdbcTemplate，风格对齐 {@link JdbcConnectionRepository}）。
 *
 * <p>表结构见 resources/schema.sql 的 {@code online_connection}，启动时由 spring.sql.init 自动建立。</p>
 */
@Repository
public class OnlineConnectionRepository {
    private final JdbcTemplate jdbc;

    public OnlineConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<OnlineConnection> map = (r, n) -> {
        OnlineConnection c = new OnlineConnection();
        c.setId(r.getLong("id"));
        c.setAreaCode(r.getInt("area_code"));
        c.setRegionName(r.getString("region_name"));
        c.setDbType(r.getString("db_type"));
        c.setDisplayName(r.getString("display_name"));
        c.setVer(r.getString("ver"));
        c.setWriteBaseUrl(r.getString("write_base_url"));
        c.setEnabled(r.getBoolean("enabled"));
        c.setRemark(r.getString("remark"));
        return c;
    };

    public List<OnlineConnection> find(String keyword) {
        String k = keyword == null || keyword.trim().isEmpty() ? null : "%" + keyword.trim() + "%";
        return jdbc.query("select * from online_connection where (? is null or region_name like ? or display_name like ? or cast(area_code as char) like ?) order by area_code asc",
                map, k, k, k, k);
    }

    /** 供后续「字段对比接入线上」批量抓结构使用：只取启用的区域。 */
    public List<OnlineConnection> findEnabled() {
        return jdbc.query("select * from online_connection where enabled=1 order by area_code asc", map);
    }

    public Optional<OnlineConnection> findById(Long id) {
        return jdbc.query("select * from online_connection where id=?", map, id).stream().findFirst();
    }

    public boolean exists(String ver, Integer areaCode) {
        Integer n = jdbc.queryForObject("select count(*) from online_connection where ver=? and area_code=?",
                Integer.class, ver, areaCode);
        return n != null && n > 0;
    }

    public OnlineConnection save(OnlineConnection c) {
        if (c.getId() == null) {
            KeyHolder h = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement p = con.prepareStatement(
                        "insert into online_connection(area_code,region_name,db_type,display_name,ver,write_base_url,enabled,remark) values(?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                bind(p, c);
                return p;
            }, h);
            Number id = h.getKey();
            if (id == null) throw new IllegalStateException("新增线上环境失败：数据库未返回自增主键");
            c.setId(id.longValue());
        } else {
            jdbc.update("update online_connection set area_code=?,region_name=?,db_type=?,display_name=?,ver=?,write_base_url=?,enabled=?,remark=? where id=?",
                    p -> {
                        bind(p, c);
                        p.setLong(9, c.getId());
                    });
        }
        return c;
    }

    private void bind(PreparedStatement p, OnlineConnection c) throws java.sql.SQLException {
        p.setInt(1, c.getAreaCode() == null ? 0 : c.getAreaCode());
        p.setString(2, c.getRegionName());
        p.setString(3, c.getDbType() == null || c.getDbType().trim().isEmpty() ? "mysql" : c.getDbType().trim());
        p.setString(4, c.getDisplayName());
        p.setString(5, c.getVer() == null || c.getVer().trim().isEmpty() ? "BT21" : c.getVer().trim());
        p.setString(6, c.getWriteBaseUrl());
        p.setBoolean(7, c.getEnabled() == null || c.getEnabled());
        p.setString(8, c.getRemark());
    }

    public void delete(Long id) {
        jdbc.update("delete from online_connection where id=?", id);
    }
}
