package com.sxwh.sqlcontroller.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 为已部署过旧版侧边栏表的配置库做向后兼容升级。
 * 新库由 schema.sql 直接建表；旧库会在这里把 type/open 迁移为 node_type/is_open。
 */
@Component
public class ScriptSchemaMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public ScriptSchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrateScriptNode();
        migrateHistory();
        migrateOnlineConnection();
    }

    /**
     * 线上区域表：db_type 是后加的（后续字段对比同步要用目标方言）。
     * 早期版本建表时没有这一列，这里补齐，避免老库升级后启动即报 Unknown column。
     */
    private void migrateOnlineConnection() {
        Set<String> columns = columns("online_connection");
        if (columns.isEmpty()) return;
        if (!columns.contains("db_type"))
            jdbc.execute("ALTER TABLE online_connection ADD COLUMN db_type VARCHAR(20) NOT NULL DEFAULT 'mysql' COMMENT '该区域库类型：mysql/dm/oscar' AFTER region_name");
    }

    private void migrateScriptNode() {
        Set<String> columns = columns("script_node");
        if (columns.isEmpty()) return;
        if (columns.contains("type") && !columns.contains("node_type")) {
            jdbc.execute("ALTER TABLE script_node CHANGE COLUMN type node_type VARCHAR(10) NOT NULL COMMENT '节点类型：folder 或 file'");
        }
        columns = columns("script_node");
        if (columns.contains("open") && !columns.contains("is_open")) {
            jdbc.execute("ALTER TABLE script_node CHANGE COLUMN `open` is_open TINYINT(1) NOT NULL DEFAULT 1 COMMENT '文件夹是否展开'");
        }
        columns = columns("script_node");
        if (!columns.contains("sort_order"))
            jdbc.execute("ALTER TABLE script_node ADD COLUMN sort_order INT NOT NULL DEFAULT 0 COMMENT '同级节点排序值'");
        jdbc.update("insert ignore into script_node(id,node_type,name,parent_id,sql_text,sort_order,is_open) values(1,'folder','默认分类',null,null,0,1)");
    }

    private void migrateHistory() {
        Set<String> columns = columns("sql_execution_history");
        if (columns.isEmpty()) return;
        if (!columns.contains("script_node_id"))
            jdbc.execute("ALTER TABLE sql_execution_history ADD COLUMN script_node_id BIGINT NULL COMMENT '关联脚本节点 ID' AFTER connection_id");
        if (!columns.contains("script_name"))
            jdbc.execute("ALTER TABLE sql_execution_history ADD COLUMN script_name VARCHAR(200) NULL COMMENT '执行脚本名称' AFTER script_node_id");
        if (columns.contains("params_summary"))
            jdbc.execute("ALTER TABLE sql_execution_history DROP COLUMN params_summary");
        if (!columns.contains("client_ip"))
            jdbc.execute("ALTER TABLE sql_execution_history ADD COLUMN client_ip VARCHAR(64) NULL COMMENT '操作人 IP（X-Forwarded-For 首段）' AFTER affected_rows");
        // 线上写入也要进历史（审计），其 connection_id 为空 → 老库的 NOT NULL 需放开（仅必要时执行）
        if (connectionIdRequired())
            jdbc.execute("ALTER TABLE sql_execution_history MODIFY COLUMN connection_id BIGINT NULL COMMENT '数据库连接 ID（线上写入为空）'");
    }

    /** sql_execution_history.connection_id 是否仍为 NOT NULL（老库）。 */
    private boolean connectionIdRequired() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SHOW COLUMNS FROM sql_execution_history LIKE 'connection_id'");
            for (Map<String, Object> row : rows) {
                return !"YES".equalsIgnoreCase(String.valueOf(row.get("Null")));
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private Set<String> columns(String table) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SHOW COLUMNS FROM " + table);
            Set<String> result = new HashSet<>();
            for (Map<String, Object> row : rows) result.add(String.valueOf(row.get("Field")).toLowerCase());
            return result;
        } catch (Exception ignored) {
            return new HashSet<>();
        }
    }
}
