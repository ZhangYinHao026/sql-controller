package com.sxwh.sqlcontroller.dialect;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL 方言（源方言基准）：类型恒等映射，主要用于源侧 SQL 转换。
 */
@Component
public class MySqlDialect extends AbstractDialect {

    /** MySQL→MySQL 恒等映射（转换的源方言，占位）。 */
    private static final Map<String, String> TYPE_MAP = new HashMap<>();

    static {
        // 所有类型原样保留（mapType default 返回 mysqlType）
    }

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    protected Map<String, String> typeMap() {
        return TYPE_MAP;
    }

    @Override
    protected List<String> reservedWords() {
        return Collections.emptyList();
    }

    /** 字段对比快照批量列查询：一条拉全库列（含主键/自增/注释/可空），服务端按 table_name 分组。 */
    @Override
    public String schemaAllColumnsQuery() {
        return "SELECT table_name, column_name, column_type AS data_type, is_nullable, column_key, extra, column_comment "
                + "FROM information_schema.columns WHERE table_schema = ? ORDER BY table_name, ordinal_position";
    }

}
