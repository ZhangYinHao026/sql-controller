package com.sxwh.sqlcontroller.dialect;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.sxwh.sqlcontroller.model.DatabaseConnection;

import java.util.Collections;
import java.util.List;

/**
 * MySQL 到目标数据库的 SQL 转换方言策略。
 *
 * <p>新增数据库 = 新增一个实现类 + {@code @Component}，无需改动现有逻辑（开闭原则）。
 * 方言差异点仅包含：类型映射 / 保留字引号 / 自增子句 / 注释语句。
 * JDBC 驱动、连接 URL、连通性与分页由 {@code platform.JdbcPlatform} 管理。
 */
public interface Dialect {

    /** 目标方言名称：dm / oscar / mysql ...（与 DatabaseConnection.dbType 一致）。 */
    String name();

    /** MySQL 类型 → 本方言类型（含长度/精度参数处理）。 */
    String mapType(String mysqlType, List<SQLExpr> args);

    /** 保留字加引号，普通标识符原样返回。 */
    String quote(String identifier);

    /** AUTO_INCREMENT 对应的本方言自增子句，如 IDENTITY(1,1)（仅 pseudoTypeIdentity()==false 时使用）。 */
    String identityClause();

    /** 是否用「伪类型」表达自增列（如神通 PG 模式 SERIAL/BIGSERIAL）：列类型被整体替换，不再追加 identityClause()。 */
    default boolean pseudoTypeIdentity() {
        return false;
    }

    /** pseudoTypeIdentity()==true 时：MySQL AUTO_INCREMENT 列的源类型名（大写，如 INT/BIGINT）→ 伪类型名。 */
    default String identityPseudoType(String mysqlType) {
        return "BIGINT".equalsIgnoreCase(mysqlType) ? "BIGSERIAL" : "SERIAL";
    }

    /** 生成列注释语句（要求表已存在）。 */
    String commentOnColumn(String table, String column, String comment);

    /** 生成表注释语句（要求表已存在）。 */
    String commentOnTable(String table, String comment);

    /** 该 MySQL 类型是否在本方言已知类型集合中（用于 warning 判断）。 */
    boolean isKnownType(String mysqlType);

    /** 表清单查询 SQL（参数：schema 名；默认走 information_schema）。 */
    default String schemaTablesQuery() {
        return "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name";
    }

    /** 字段清单查询 SQL（参数：schema 名；默认走 information_schema）。 */
    default String schemaColumnsQuery() {
        return "SELECT table_name, column_name, data_type FROM information_schema.columns WHERE table_schema = ? ORDER BY table_name, ordinal_position";
    }

    /** 联想元数据查询所用的 schema 标识（MySQL/DM=库名；Oscar=用户名）。 */
    default String schemaName(DatabaseConnection c) {
        return c.getDatabaseName();
    }

    /**
     * 单表列详情查询（字段对比快照用；参数：schema 名、表名）。
     *
     * <p>MySQL 默认走 information_schema.columns 一行带全量语义字段；非 MySQL 方言应覆盖为
     * 各自数据字典写法（如 all_tab_columns）。返回行中可识别的列（大小写不敏感）：
     * column_name / column_type 或 data_type / is_nullable 或 nullable / column_key(PRI) /
     * extra(含 auto_increment) / column_comment / column_default 或 data_default。
     */
    default String schemaColumnDetailQuery() {
        return "SELECT column_name, column_type AS data_type, is_nullable, column_key, extra, column_comment, column_default "
                + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
    }

    /**
     * 主键列探测 SQL（字段对比快照用；参数：schema 名、表名）。返回行需含 column_name 列。
     * 返回 null=不做主键探测（详情查询本身能给出 primary key 语义的方言，如 MySQL）。
     */
    default String schemaPkColumnsQuery() {
        return null;
    }

    /**
     * 列注释探测 SQL（字段对比快照用；参数：schema 名、表名）。返回行需含 column_name 与
     * comments（或 comment）列。返回 null=不做注释探测（如 MySQL 详情查询已含注释）。
     */
    default String schemaColumnCommentQuery() {
        return null;
    }

    /**
     * 全库列详情批量查询（字段对比快照用；参数：schema 名，不带表名条件）。
     *
     * <p>返回行除单表详情（见 {@link #schemaColumnDetailQuery()}）的可识别字段外，
     * 还必须含 table_name 列，服务端一次拉全库后在内存按表分组。仅当方言字典支持全量拉取时覆盖
     * （MySQL=information_schema.columns；DM/神通=all_tab_columns）；返回 null 表示不支持，服务端回退逐表查询。
     */
    default String schemaAllColumnsQuery() {
        return null;
    }

    /**
     * 全库主键列批量探测（字段对比快照用；参数：schema 名，不带表名条件）。
     * 返回行需含 table_name 与 column_name；null=不做批量主键探测（回退逐表或降级跳过）。
     */
    default String schemaAllPkColumnsQuery() {
        return null;
    }

    /**
     * 全库列注释批量探测（字段对比快照用；参数：schema 名，不带表名条件）。
     * 返回行需含 table_name、column_name、comments（或 comment）；null=不做批量注释探测（回退逐表或降级跳过）。
     */
    default String schemaAllColumnCommentsQuery() {
        return null;
    }

    /**
     * 全库自增列批量探测（字段对比快照用；参数：schema 名，不带表名条件）。
     * 返回行需含 table_name 与 column_name；null=不做自增探测（autoIncrement 保持未探测=null，前端不生成 AUTO_INCREMENT）。
     *
     * <p>MySQL 走 information_schema 的 extra 列即可，无需本条；DM/神通等字典不暴露自增标识时才需要。
     */
    default String schemaAllIdentityColumnsQuery() {
        return null;
    }

    /**
     * 全库索引批量查询（字段对比快照用；参数：schema 名，不带表名条件）。
     *
     * <p>返回行需含：table_name、index_name、column_name、column_position，以及下列任一组合用于
     * 判定唯一性/主键：{@code uniqueness}(UNIQUE/NONUNIQUE) 或 {@code non_unique}(0/1)，
     * 可选 {@code constraint_type}(P/U) 与 {@code constraint_name}（唯一约束优先用约束名，如 DM 的 uk_xxx），
     * 可选 {@code index_type}。服务端按表名+索引名分组、按 position 排序，一次查询拉全库。
     * 返回 null 表示方言不支持（快照不含索引，前端不生成索引）。
     */
    default String schemaAllIndexesQuery() {
        return "SELECT s.table_name, s.index_name, s.column_name, s.seq_in_index AS column_position, "
                + "s.non_unique, s.index_type, "
                + "CASE WHEN s.index_name = 'PRIMARY' THEN 'P' ELSE NULL END AS constraint_type, "
                + "NULL AS constraint_name "
                + "FROM information_schema.statistics s WHERE s.table_schema = ? "
                + "ORDER BY s.table_name, s.index_name, s.seq_in_index";
    }

    /**
     * 全库表注释批量查询（字段对比快照用；参数：schema 名）。
     * 返回行需含 table_name 与 comments（或 table_comment）；null=不做表注释探测。
     */
    default String schemaAllTableCommentsQuery() {
        return "SELECT table_name, table_comment AS comments FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_type = 'BASE TABLE' ORDER BY table_name";
    }

    /**
     * MySQL {@code ALTER TABLE ... MODIFY COLUMN} 改写为本方言的「改列」语句集合（完整 ALTER 语句，可多条）。
     *
     * @param table  已加引号的表名
     * @param column 已加引号的列名
     * @param type   已映射的类型表达式（含自增伪类型/IDENTITY 处理）
     * @param notNull true=显式 NOT NULL；false=显式 NULL/去除非空；null=未声明
     * @param defaultExpr 默认值表达式原文；null=未声明
     */
    default List<String> alterColumnStatements(String table, String column, String type,
                                               Boolean notNull, String defaultExpr) {
        StringBuilder s = new StringBuilder("ALTER TABLE ").append(table)
                .append(" MODIFY COLUMN ").append(column).append(" ").append(type);
        if (Boolean.TRUE.equals(notNull)) {
            s.append(" NOT NULL");
        } else if (Boolean.FALSE.equals(notNull)) {
            s.append(" NULL");
        }
        if (defaultExpr != null) {
            s.append(" DEFAULT ").append(defaultExpr);
        }
        return Collections.singletonList(s.toString());
    }
}
