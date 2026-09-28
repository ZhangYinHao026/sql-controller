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
        return "SELECT column_name, data_type, nullable, char_length, data_length, data_precision, data_scale, data_default "
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
        return "SELECT table_name, column_name, data_type, nullable, char_length, data_length, data_precision, data_scale, data_default "
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

    /**
     * 全库自增列批量探测（参数：schema 名）。
     *
     * <p>达梦的 all_tab_columns 不暴露自增标识，自增信息在系统表 syscolumns.info2 的最低位
     * （实测 {id} IDENTITY(1,1) → info2=1，普通列=0）。syscolumns 无 owner 列，需经 sysobjects
     * 关联到当前模式：模式对象是一条 name=模式名、pid 指向该 USER 对象的记录
     * （USER 对象 subtype$='USER'），其 id 即表的 schid。
     */
    @Override
    public String schemaAllIdentityColumnsQuery() {
        return "SELECT o.name AS table_name, c.name AS column_name FROM syscolumns c, sysobjects o "
                + "WHERE c.id = o.id AND bitand(c.info2, 1) = 1 "
                + "AND o.schid IN (SELECT s.id FROM sysobjects s, sysobjects u "
                + "WHERE s.name = ? AND s.pid = u.id AND u.subtype$ = 'USER')";
    }

    /**
     * 全库索引批量探测（参数：schema 名）。
     *
     * <p>三表联查：all_indexes 给唯一性/类型，all_ind_columns 给索引列与列序，
     * all_constraints 给约束名与约束类型（P=主键、U=唯一）——达梦的唯一约束索引用的是系统生成名
     * （INDEX33611650），约束名才是人写的 uk_sys_upload_file_name，下游按约束名重建更可读。
     * 纯索引（无约束）约束名为 null，退回索引名。INNER JOIN all_ind_columns 会自然滤掉
     * 无列信息的特殊索引（如达梦 CLUSTER 类型内部索引），避免生成无列索引。
     */
    @Override
    public String schemaAllIndexesQuery() {
        return "SELECT i.table_name, i.index_name, i.uniqueness, i.index_type, "
                + "c.constraint_name, c.constraint_type, ic.column_name, ic.column_position "
                + "FROM all_indexes i "
                + "JOIN all_ind_columns ic ON ic.index_owner = i.owner AND ic.index_name = i.index_name "
                + "AND ic.table_name = i.table_name "
                + "LEFT JOIN all_constraints c ON c.owner = i.owner AND c.table_name = i.table_name "
                + "AND c.index_name = i.index_name "
                + "WHERE i.owner = ? ORDER BY i.table_name, i.index_name, ic.column_position";
    }

    @Override
    public String schemaAllTableCommentsQuery() {
        return "SELECT table_name, comments FROM all_tab_comments WHERE owner = ? AND table_name IS NOT NULL "
                + "ORDER BY table_name";
    }

}
