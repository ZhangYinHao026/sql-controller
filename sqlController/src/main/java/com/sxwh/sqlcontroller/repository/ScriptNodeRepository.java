package com.sxwh.sqlcontroller.repository;

import com.sxwh.sqlcontroller.model.ScriptNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

/**
 * SQL 脚本树节点的数据访问层。
 */
@Repository
public class ScriptNodeRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<ScriptNode> mapper = (rs, rowNum) -> {
        ScriptNode node = new ScriptNode();
        node.setId(rs.getLong("id"));
        node.setNodeType(rs.getString("node_type"));
        node.setName(rs.getString("name"));
        long parentId = rs.getLong("parent_id");
        node.setParentId(rs.wasNull() ? null : parentId);
        node.setSqlText(rs.getString("sql_text"));
        node.setSortOrder(rs.getInt("sort_order"));
        node.setIsOpen(rs.getBoolean("is_open"));
        return node;
    };

    public ScriptNodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ScriptNode> findAll() {
        return jdbc.query("select id,node_type,name,parent_id,sql_text,sort_order,is_open from script_node order by parent_id, sort_order, id", mapper);
    }

    public Optional<ScriptNode> findById(Long id) {
        return jdbc.query("select id,node_type,name,parent_id,sql_text,sort_order,is_open from script_node where id=?", mapper, id).stream().findFirst();
    }

    public boolean existsSiblingName(Long parentId, String name, Long excludedId) {
        String sql = "select count(1) from script_node where name=? and ((parent_id is null and ? is null) or parent_id=?) and (? is null or id<>?)";
        Integer count = jdbc.queryForObject(sql, Integer.class, name, parentId, parentId, excludedId, excludedId);
        return count != null && count > 0;
    }

    public ScriptNode insert(ScriptNode node) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "insert into script_node(node_type,name,parent_id,sql_text,sort_order,is_open) values(?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            bind(statement, node);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("新增脚本节点失败：数据库未返回主键");
        node.setId(key.longValue());
        return node;
    }

    public ScriptNode update(ScriptNode node) {
        jdbc.update("update script_node set node_type=?,name=?,parent_id=?,sql_text=?,sort_order=?,is_open=? where id=?", statement -> {
            bind(statement, node);
            statement.setLong(7, node.getId());
        });
        return node;
    }

    public void updateOpen(Long id, boolean open) {
        jdbc.update("update script_node set is_open=? where id=?", open, id);
    }

    public void updateMove(Long id, Long parentId, int sortOrder) {
        jdbc.update("update script_node set parent_id=?,sort_order=? where id=?", statement -> {
            if (parentId == null) statement.setNull(1, Types.BIGINT);
            else statement.setLong(1, parentId);
            statement.setInt(2, sortOrder);
            statement.setLong(3, id);
        });
    }

    public void delete(Long id) {
        jdbc.update("delete from script_node where id=?", id);
    }

    private void bind(PreparedStatement statement, ScriptNode node) throws java.sql.SQLException {
        statement.setString(1, node.getNodeType());
        statement.setString(2, node.getName());
        if (node.getParentId() == null) statement.setNull(3, Types.BIGINT);
        else statement.setLong(3, node.getParentId());
        statement.setString(4, node.getSqlText());
        statement.setInt(5, node.getSortOrder());
        statement.setBoolean(6, node.getIsOpen());
    }
}
