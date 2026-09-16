package com.sxwh.sqlcontroller.dialect;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 达梦 DM8 方言。 */
@Component
public class DmDialect extends AbstractDialect {

    private static final Map<String, String> TYPE_MAP = new HashMap<>();

    static {
        TYPE_MAP.put("INT", "INT");
        TYPE_MAP.put("INTEGER", "INT");
        TYPE_MAP.put("MEDIUMINT", "INT");
        TYPE_MAP.put("BIGINT", "BIGINT");
        TYPE_MAP.put("SMALLINT", "SMALLINT");
        TYPE_MAP.put("TINYINT", "SMALLINT");
        TYPE_MAP.put("BOOLEAN", "SMALLINT");
        TYPE_MAP.put("BOOL", "SMALLINT");
        TYPE_MAP.put("BIT", "SMALLINT");
        TYPE_MAP.put("TEXT", "CLOB");
        TYPE_MAP.put("TINYTEXT", "CLOB");
        TYPE_MAP.put("MEDIUMTEXT", "CLOB");
        TYPE_MAP.put("LONGTEXT", "CLOB");
        TYPE_MAP.put("DATETIME", "TIMESTAMP");
        TYPE_MAP.put("TIMESTAMP", "TIMESTAMP");
        TYPE_MAP.put("DATE", "DATE");
        TYPE_MAP.put("TIME", "TIME");
        TYPE_MAP.put("FLOAT", "FLOAT");
        TYPE_MAP.put("DOUBLE", "DOUBLE");
        TYPE_MAP.put("BLOB", "BLOB");
        TYPE_MAP.put("TINYBLOB", "BLOB");
        TYPE_MAP.put("MEDIUMBLOB", "BLOB");
        TYPE_MAP.put("LONGBLOB", "BLOB");
    }

    @Override
    public String name() {
        return "dm";
    }

    /** 达梦默认大小写敏感、不带引号标识符自动转大写存储，输出统一大写保持与库内一致。 */
    @Override
    protected boolean uppercaseIdentifiers() {
        return true;
    }

    @Override
    protected Map<String, String> typeMap() {
        return TYPE_MAP;
    }

    @Override
    protected List<String> reservedWords() {
        return Arrays.asList(
                "LEVEL", "TYPE", "COMMENT", "RANK", "SOURCE", "SIZE", "GROUP", "ORDER", "USER",
                "TABLE", "VALUE", "DATE", "TIME", "ROWNUM", "NUMBER", "ROW", "SCHEMA", "LOCATION",
                "VARCHAR2", "BLOB", "CLOB", "PRIMARY", "NULL", "NOT", "DEFAULT", "CHECK");
    }

    // ==================== 字段对比快照：数据字典查询 ====================
    // 达梦兼容 Oracle 数据字典：ALL_TABLES / ALL_TAB_COLUMNS / ALL_CONSTRAINTS / ALL_COL_COMMENTS。
    // 注意：DM 不识别 information_schema 模式名（父类默认表清单查询会失败），必须走 all_tables；
    // 详情查询只取名称/类型/可空；主键与注释因字典不同需单独探测（下方两个查询）。

    @Override
    public String schemaTablesQuery() {
        return "SELECT table_name FROM all_tables WHERE owner = ? ORDER BY table_name";
    }

    @Override
    public String schemaColumnDetailQuery() {
        return "SELECT column_name, data_type, nullable, char_length, data_precision, data_scale "
                + "FROM all_tab_columns WHERE owner = ? AND table_name = ? ORDER BY column_id";
    }

    @Override
    public String schemaPkColumnsQuery() {
        return "SELECT cc.column_name FROM all_constraints c "
                + "JOIN all_cons_columns cc ON cc.constraint_name = c.constraint_name AND cc.owner = c.owner "
                + "WHERE c.owner = ? AND c.table_name = ? AND c.constraint_type = 'P' ORDER BY cc.position";
    }

    @Override
    public String schemaColumnCommentQuery() {
        return "SELECT column_name, comments FROM all_col_comments WHERE owner = ? AND table_name = ?";
    }

    // 批量版（字段对比快照根治 N+1）：固定 3 条拉全库列/主键/注释，服务端按 table_name 分组
    @Override
    public String schemaAllColumnsQuery() {
        return "SELECT table_name, column_name, data_type, nullable, char_length, data_precision, data_scale "
                + "FROM all_tab_columns WHERE owner = ? ORDER BY table_name, column_id";
    }

    @Override
    public String schemaAllPkColumnsQuery() {
        return "SELECT cc.table_name, cc.column_name FROM all_constraints c "
                + "JOIN all_cons_columns cc ON cc.constraint_name = c.constraint_name AND cc.owner = c.owner "
                + "WHERE c.owner = ? AND c.constraint_type = 'P' ORDER BY cc.table_name, cc.position";
    }

    @Override
    public String schemaAllColumnCommentsQuery() {
        return "SELECT table_name, column_name, comments FROM all_col_comments WHERE owner = ? "
                + "ORDER BY table_name, column_name";
    }

}
