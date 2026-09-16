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
