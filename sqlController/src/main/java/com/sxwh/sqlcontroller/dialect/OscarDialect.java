package com.sxwh.sqlcontroller.dialect;

import com.sxwh.sqlcontroller.model.DatabaseConnection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 神通 Oscar 方言。 */
@Component
public class OscarDialect extends AbstractDialect {

    private static final Map<String, String> TYPE_MAP = new HashMap<>();

    static {
        TYPE_MAP.put("INT", "INTEGER");
        TYPE_MAP.put("INTEGER", "INTEGER");
        TYPE_MAP.put("MEDIUMINT", "INTEGER");
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
        return "oscar";
    }

    /** 神通默认大小写敏感（同 Oracle），不带引号标识符自动转大写存储，输出统一大写保持与库内一致。 */
    @Override
    protected boolean uppercaseIdentifiers() {
        return true;
    }

    /** 神通（PG 内核）不支持达梦的 IDENTITY(1,1) 语法，自增列用伪类型 SERIAL/BIGSERIAL 整体替换类型。 */
    @Override
    public boolean pseudoTypeIdentity() {
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

    // 神通兼容 Oracle 数据字典：ALL_TABLES / ALL_TAB_COLUMNS（schema 标识为用户名；列名需真库实测）
    @Override
    public String schemaTablesQuery() {
        return "SELECT table_name FROM all_tables WHERE owner = ? ORDER BY table_name";
    }

    @Override
    public String schemaColumnsQuery() {
        return "SELECT table_name, column_name, data_type FROM all_tab_columns WHERE owner = ? ORDER BY table_name, column_id";
    }

    // 字段对比快照：详情/主键/注释分走三个字典查询；主键与注释查询若库不支持会抛错，服务侧捕获后降级跳过
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

    @Override
    public String schemaName(DatabaseConnection c) {
        return c.getUsername();
    }

    /**
     * 神通（PG 内核）不支持 MySQL 融合式 {@code MODIFY COLUMN ...}，改列需按语义拆分独立语句：
     * TYPE 变类型；SET/DROP NOT NULL 变可空；SET DEFAULT 变默认值。注释由调用方在全部 ALTER 后追加 COMMENT ON。
     */
    @Override
    public List<String> alterColumnStatements(String table, String column, String type,
                                              Boolean notNull, String defaultExpr) {
        List<String> out = new ArrayList<>();
        String base = "ALTER TABLE " + table;
        out.add(base + " ALTER COLUMN " + column + " TYPE " + type);
        if (Boolean.TRUE.equals(notNull)) {
            out.add(base + " ALTER COLUMN " + column + " SET NOT NULL");
        } else if (Boolean.FALSE.equals(notNull)) {
            out.add(base + " ALTER COLUMN " + column + " DROP NOT NULL");
        }
        if (defaultExpr != null) {
            out.add(base + " ALTER COLUMN " + column + " SET DEFAULT " + defaultExpr);
        }
        return out;
    }
}
