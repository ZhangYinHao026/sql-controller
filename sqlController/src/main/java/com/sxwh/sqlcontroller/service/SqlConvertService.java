package com.sxwh.sqlcontroller.service;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableAddColumn;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableAddIndex;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableAddConstraint;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableAlterColumn;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableDropColumnItem;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableItem;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLColumnDefinition;
import com.alibaba.druid.sql.ast.statement.SQLCommentStatement;
import com.alibaba.druid.sql.ast.statement.SQLConstraint;
import com.alibaba.druid.sql.ast.statement.SQLCreateIndexStatement;
import com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLDropTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLForeignKeyConstraint;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLPrimaryKey;
import com.alibaba.druid.sql.ast.statement.SQLSelectOrderByItem;
import com.alibaba.druid.sql.ast.statement.SQLTableElement;
import com.alibaba.druid.sql.ast.statement.SQLUniqueConstraint;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlAlterTableModifyColumn;
import com.alibaba.druid.sql.parser.ParserException;
import com.sxwh.sqlcontroller.dialect.AbstractDialect;
import com.sxwh.sqlcontroller.dialect.Dialect;
import com.sxwh.sqlcontroller.dialect.DialectRegistry;
import com.sxwh.sqlcontroller.model.ConvertResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 方言转换核心服务：MySQL → 目标方言（达梦/神通/…）。
 *
 * <p>方言差异（类型映射 / 保留字引号 / 自增子句 / 注释语句）全部委托给 {@link Dialect} 策略，
 * 本类只保留转换流程，符合开闭原则——新增数据库无需改动本类。
 *
 * <p>支持范围：
 * <ul>
 *   <li>CREATE TABLE（列/自增/默认值/注释拆分 COMMENT ON/索引拆分 CREATE INDEX/剥离 ENGINE 等表选项）</li>
 *   <li>ALTER TABLE ... ADD COLUMN（列注释同样拆分）/ ADD INDEX / DROP COLUMN / MODIFY COLUMN</li>
 *   <li>独立 CREATE INDEX / CREATE UNIQUE INDEX 语句</li>
 *   <li>简单 DML 直通：INSERT 单行/多值、UPDATE 单表、DELETE 单表、DROP TABLE</li>
 * </ul>
 * MySQL 特有语法（JOIN 更新、LIMIT、ON DUPLICATE KEY 等）不静默修改，统一收集 warning。
 */
@Service
public class SqlConvertService {

    /** 表级注释：COMMENT='xxx'（位于建表语句括号之后的表选项中）。 */
    private static final Pattern TABLE_COMMENT = Pattern.compile("(?i)\\bCOMMENT\\s*=\\s*['\"]([^'\"]*)['\"]");
    /** 表内索引定义：KEY / INDEX / UNIQUE KEY。 */
    private static final Pattern INDEX_DEF = Pattern.compile("(?i)^\\s*(UNIQUE\\s+)?(KEY|INDEX)\\b(.*)$");
    /** 括号内列清单：(col1, col2)。 */
    private static final Pattern PAREN_COLS = Pattern.compile("\\(([^()]*)\\)");

    private final DialectRegistry registry;

    public SqlConvertService(DialectRegistry registry) {
        this.registry = registry;
    }

    /** 入口：转换整段 SQL。 */
    public ConvertResult convert(String sql, String target) {
        ConvertResult out = new ConvertResult(target);
        try {
            Dialect dialect = registry.get(target);
            List<SQLStatement> stmts = SQLUtils.parseStatements(sql, DbType.mysql);
            for (SQLStatement st : stmts) {
                if (st instanceof SQLCreateTableStatement) {
                    convertCreateTable((SQLCreateTableStatement) st, dialect, out);
                } else if (st instanceof SQLAlterTableStatement) {
                    convertAlter((SQLAlterTableStatement) st, dialect, out);
                } else if (st instanceof SQLInsertStatement || st instanceof SQLUpdateStatement
                        || st instanceof SQLDeleteStatement || st instanceof SQLDropTableStatement) {
                    passThrough(st, out);
                } else if (st instanceof SQLCommentStatement) {
                    convertComment((SQLCommentStatement) st, dialect, out);
                } else if (st instanceof SQLCreateIndexStatement) {
                    convertCreateIndex((SQLCreateIndexStatement) st, dialect, out);
                } else {
                    warn(out, "不支持的语句类型 " + st.getClass().getSimpleName() + "，已跳过");
                }
            }
            out.setSuccess(true);
            stripStatementSemicolons(out);
        } catch (ParserException e) {
            out.setSuccess(false);
            out.setError("SQL 解析失败：" + e.getMessage());
        } catch (Exception e) {
            out.setSuccess(false);
            out.setError("转换失败：" + e.getMessage());
        }
        return out;
    }

    /** 去掉每个语句末尾的分号/空白（前端 join ";\n" 时不重复，避免显示 ";;"）。 */
    private void stripStatementSemicolons(ConvertResult out) {
        List<String> list = out.getStatements();
        for (int i = 0; i < list.size(); i++) {
            String s = list.get(i);
            int end = s.length();
            while (end > 0) {
                char c = s.charAt(end - 1);
                if (c == ';' || c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    end--;
                } else {
                    break;
                }
            }
            list.set(i, s.substring(0, end));
        }
    }

    // ==================== CREATE TABLE ====================

    private void convertCreateTable(SQLCreateTableStatement ct, Dialect dialect, ConvertResult out) {
        String table = dialect.quote(AbstractDialect.unquote(ct.getTableSource().getTableName()));
        List<String> parts = new ArrayList<>();
        List<String> comments = new ArrayList<>();   // 列注释：等建表语句之后输出
        List<String> indexes = new ArrayList<>();    // 索引：等建表语句之后输出
        // 1) 列定义
        // 先收集表级主键列：Druid 解析 MySQL 会把表级 PRIMARY KEY (a, b) 同时标记到列的 isPrimaryKey，
        // 若不排除，同一主键会被输出两份（列级 PRIMARY KEY + 表级 PRIMARY KEY），目标库建表报"重复定义主键"。
        // 统一以表级 SQLPrimaryKey 为准：属于它的列，列级不再追加 PRIMARY KEY。
        Set<String> tablePkCols = new HashSet<>();
        if (ct.getTableElementList() != null) {
            for (SQLTableElement el : ct.getTableElementList()) {
                if (el instanceof SQLPrimaryKey) {
                    for (Object c : ((SQLPrimaryKey) el).getColumns()) {
                        tablePkCols.add(colKey(c));
                    }
                }
            }
        }
        for (SQLColumnDefinition col : ct.getColumnDefinitions()) {
            boolean inTablePk = tablePkCols.contains(colKey(col.getName()));
            parts.add(buildColumn(col, dialect, table, out, comments, inTablePk));
        }
        // 2) 表级约束与索引（列定义之外的 tableElement）
        if (ct.getTableElementList() != null) {
            for (SQLTableElement el : ct.getTableElementList()) {
                if (el instanceof SQLColumnDefinition) {
                    continue; // 列已处理
                }
                if (el instanceof SQLPrimaryKey) {
                    // 表级主键约束必须带括号：PRIMARY KEY (id)。colList 只返回列名清单，括号在此补齐。
                    parts.add("PRIMARY KEY (" + colList(((SQLPrimaryKey) el).getColumns(), dialect) + ")");
                } else if (el instanceof SQLUniqueConstraint) {
                    // Druid MySQL 方言下：KEY idx (col) 和 UNIQUE KEY uk (col) 都被解析为 SQLUniqueConstraint。
                    // 按 toString() 是否含 UNIQUE 关键字区分：含→CONSTRAINT UNIQUE；不含→普通索引 CREATE INDEX
                    SQLUniqueConstraint uk = (SQLUniqueConstraint) el;
                    String text = el.toString().toUpperCase();
                    if (text.contains("UNIQUE")) {
                        String nm = uk.getName() == null ? "" : dialect.quote(uk.getName().getSimpleName()) + " ";
                        parts.add("CONSTRAINT " + nm + "UNIQUE (" + colList(uk.getColumns(), dialect) + ")");
                    } else {
                        indexes.add("CREATE INDEX " + dialect.quote(extractIndexName(el.toString()))
                                + " ON " + table + " (" + extractColumns(el.toString()) + ")");
                    }
                } else if (el instanceof SQLForeignKeyConstraint) {
                    // 外键语法各库差异小，按原样保留 + 提示核对
                    parts.add(el.toString());
                    warn(out, "外键已按原样保留，请在目标库核对");
                } else {
                    // 其他类型（如 CHECK），兜底按字符串识别 KEY/INDEX
                    String text = el.toString();
                    Matcher m = INDEX_DEF.matcher(text);
                    if (m.find()) {
                        indexes.add("CREATE INDEX " + dialect.quote(extractIndexName(m.group(3)))
                                + " ON " + table + " (" + extractColumns(m.group(3)) + ")");
                    } else {
                        parts.add(text);
                        warn(out, "表级元素未识别已按原样保留：" + text);
                    }
                }
            }
        }
        // 3) 建表语句在前（不含 ENGINE/CHARSET 等表选项）
        out.getStatements().add("CREATE TABLE " + table + " (" + String.join(", ", parts) + ")");
        // 4) 索引、列注释、表注释依次跟在建表语句之后（COMMENT ON 必须等表已存在）
        out.getStatements().addAll(indexes);
        out.getStatements().addAll(comments);
        Matcher cm = TABLE_COMMENT.matcher(ct.toString());
        if (cm.find()) {
            out.getStatements().add(dialect.commentOnTable(table, cm.group(1)));
        }
    }

    /** 生成单个列定义；列注释拆成独立 COMMENT ON COLUMN 语句，先收集到 pendingComments 由调用方在建表/ALTER 之后输出。
     *  @param inTablePk 该列同时出现在表级 PRIMARY KEY 中（Druid MySQL 解析会把表级主键传播到列级标记），
     *                  为 true 时列级不再输出 PRIMARY KEY，避免与表级主键重复定义。 */
    private String buildColumn(SQLColumnDefinition col, Dialect dialect, String table, ConvertResult out,
                               List<String> pendingComments, boolean inTablePk) {
        String name = dialect.quote(AbstractDialect.unquote(AbstractDialect.simpleName(col.getName())));
        String mysqlType = col.getDataType() == null ? "" : col.getDataType().getName().toUpperCase();
        List<SQLExpr> args = col.getDataType() == null ? null : col.getDataType().getArguments();
        String type = dialect.mapType(mysqlType, args);

        if (mysqlType.isEmpty()) {
            // 异常输入（无类型），不提示
        } else if (mysqlType.equals("ENUM") || mysqlType.equals("SET")) {
            warn(out, "列 " + col.getName() + " 类型 ENUM/SET 已转 VARCHAR，原枚举约束未保留");
        } else if (!dialect.isKnownType(mysqlType)) {
            warn(out, "列 " + col.getName() + " 类型 " + mysqlType + " 未在映射表中，已原样保留");
        }
        // 已知类型即便原样保留（INT→INT、DATE→DATE）也不提示

        // 自增表达分两种方言模型：
        //  达梦等：列类型保持 + 追加 identityClause()（INT IDENTITY(1,1)）
        //  神通(PG 内核)：不支持 IDENTITY(1,1)，用伪类型整体替换类型（BIGINT→BIGSERIAL，其余整型→SERIAL）
        StringBuilder b;
        if (col.isAutoIncrement() && dialect.pseudoTypeIdentity()) {
            String pseudo = dialect.identityPseudoType(mysqlType);
            b = new StringBuilder(name).append(" ").append(pseudo);
            warn(out, "列 " + col.getName() + " AUTO_INCREMENT 已转 " + pseudo);
        } else {
            b = new StringBuilder(name).append(" ").append(type);
            if (col.isAutoIncrement()) {
                b.append(" ").append(dialect.identityClause());
                warn(out, "列 " + col.getName() + " AUTO_INCREMENT 已转 " + dialect.identityClause());
            }
        }
        if (hasConstraint(col, "NOT NULL")) {
            b.append(" NOT NULL");
        }
        if (col.getDefaultExpr() != null) {
            b.append(" DEFAULT ").append(col.getDefaultExpr().toString());
        }
        if (col.isPrimaryKey() && !inTablePk) {
            b.append(" PRIMARY KEY");
        }
        if (hasConstraint(col, "UNIQUE")) {
            b.append(" UNIQUE");
        }
        // 列注释 → 先收集，由调用方在建表/ALTER 语句之后输出（目标库 COMMENT ON 必须等表已存在）
        String comment = commentText(col);
        if (comment != null) {
            pendingComments.add(dialect.commentOnColumn(table, name, comment));
        }
        return b.toString();
    }

    // ==================== ALTER TABLE ADD COLUMN ====================

    private void convertAlter(SQLAlterTableStatement at, Dialect dialect, ConvertResult out) {
        String table = dialect.quote(AbstractDialect.unquote(at.getTableSource().getTableName()));
        List<SQLAlterTableItem> items = at.getItems();
        if (items == null) {
            warn(out, "ALTER 语句无操作项，已跳过");
            return;
        }
        for (SQLAlterTableItem item : items) {
            if (item instanceof SQLAlterTableAddColumn) {
                SQLAlterTableAddColumn add = (SQLAlterTableAddColumn) item;
                if (add.getColumns() == null) {
                    continue;
                }
                for (SQLColumnDefinition col : add.getColumns()) {
                    List<String> comments = new ArrayList<>();
                    // ADD COLUMN 不存在表级主键重复问题；列级 PRIMARY KEY（如有）直接保留
                    String def = buildColumn(col, dialect, table, out, comments, false);
                    out.getStatements().add("ALTER TABLE " + table + " ADD " + def);
                    out.getStatements().addAll(comments); // 该列注释紧跟其 ALTER 之后
                }
            } else if (item instanceof SQLAlterTableAddIndex) {
                // ADD INDEX / ADD UNIQUE INDEX：目标库（达梦/神通）不支持 ALTER 内嵌索引，
                // 统一改写为独立 CREATE [UNIQUE] INDEX ... ON ...，与建表语句内索引的处理一致。
                SQLAlterTableAddIndex addIdx = (SQLAlterTableAddIndex) item;
                out.getStatements().add(createIndexStatement(
                        addIdx.isUnique(), addIdx.getName(), table, addIdx.getColumns(), dialect));
                if (addIdx.getUsing() != null) {
                    warn(out, "索引 USING " + addIdx.getUsing().toString() + " 为 MySQL 特有，目标库已忽略");
                }
            } else if (item instanceof SQLAlterTableDropColumnItem) {
                // DROP COLUMN 各目标库语法基本一致（达梦/神通均支持），逐列直出
                SQLAlterTableDropColumnItem drop = (SQLAlterTableDropColumnItem) item;
                if (drop.getColumns() != null) {
                    for (SQLName c : drop.getColumns()) {
                        if (c == null) {
                            continue;
                        }
                        out.getStatements().add("ALTER TABLE " + table + " DROP COLUMN "
                                + dialect.quote(AbstractDialect.unquote(AbstractDialect.simpleName(c))));
                    }
                }
            } else if (item instanceof SQLAlterTableAddConstraint) {
                // ADD UNIQUE INDEX / ADD UNIQUE：Druid 解析为 AddConstraint（内含 SQLUniqueConstraint）。
                // 有索引名→CREATE UNIQUE INDEX；无名（纯 ADD UNIQUE）→ALTER ADD UNIQUE 按目标库语法保留。
                SQLConstraint c = ((SQLAlterTableAddConstraint) item).getConstraint();
                if (c instanceof SQLUniqueConstraint) {
                    SQLUniqueConstraint uk = (SQLUniqueConstraint) c;
                    if (uk.getName() != null) {
                        out.getStatements().add(createIndexStatement(
                                true, uk.getName(), table, uk.getColumns(), dialect));
                    } else {
                        out.getStatements().add("ALTER TABLE " + table
                                + " ADD UNIQUE (" + colList(uk.getColumns(), dialect) + ")");
                        warn(out, "无名 UNIQUE 约束已按原样保留，请在目标库核对");
                    }
                } else {
                    warn(out, "ALTER 约束 " + (c == null ? "" : c.getClass().getSimpleName())
                            + " 暂不支持，已跳过");
                }
            } else if (item instanceof MySqlAlterTableModifyColumn) {
                // MySQL MODIFY COLUMN（Druid 解析为 MySQL 方言子类，定义在 getNewColumnDefinition()）
                MySqlAlterTableModifyColumn mc = (MySqlAlterTableModifyColumn) item;
                boolean repositioned = mc.isFirst() || mc.getFirstColumn() != null || mc.getAfterColumn() != null;
                convertModifyColumn(mc.getNewColumnDefinition(), repositioned, table, dialect, out);
            } else if (item instanceof SQLAlterTableAlterColumn) {
                // 其它 ALTER COLUMN 形态（如 SET DEFAULT/NOT NULL），定义在 getColumn()
                SQLAlterTableAlterColumn ac = (SQLAlterTableAlterColumn) item;
                boolean repositioned = ac.isFirst() || ac.getAfter() != null;
                convertModifyColumn(ac.getColumn(), repositioned, table, dialect, out);
            } else {
                warn(out, "ALTER 操作 " + item.getClass().getSimpleName() + " 暂不支持，已跳过");
            }
        }
    }

    /**
     * MySQL 融合式 {@code MODIFY COLUMN c <type> [NOT NULL] [DEFAULT x] [COMMENT 'y']} 的跨方言改写。
     *
     * <p>先把融合子句拆成 类型/可空/默认/注释 四要素，再交给 {@link Dialect#alterColumnStatements} 组装
     * （达梦沿用 MODIFY COLUMN 兼容语法；PG 内核神通拆 TYPE/SET·DROP NOT NULL/SET DEFAULT 多条）。
     * 注释仍拆为独立的 COMMENT ON COLUMN 语句。
     */
    private void convertModifyColumn(SQLColumnDefinition col, boolean repositioned, String table,
                                     Dialect dialect, ConvertResult out) {
        if (col == null || col.getName() == null || col.getDataType() == null) {
            warn(out, "MODIFY 列定义缺失，已跳过");
            return;
        }
        if (repositioned) {
            warn(out, "MODIFY 含 FIRST/AFTER 位置调整，目标库不支持该语义，位置变更已忽略");
        }
        String name = dialect.quote(AbstractDialect.unquote(AbstractDialect.simpleName(col.getName())));
        String mysqlType = col.getDataType().getName().toUpperCase();
        if (mysqlType.isEmpty()) {
            warn(out, "MODIFY 列 " + col.getName() + " 无类型，已跳过");
            return;
        }
        String type;
        if (col.isAutoIncrement() && dialect.pseudoTypeIdentity()) {
            type = dialect.identityPseudoType(mysqlType);
        } else {
            type = dialect.mapType(mysqlType, col.getDataType().getArguments());
            if (col.isAutoIncrement()) {
                type += " " + dialect.identityClause();
            }
        }
        String upper = col.toString().toUpperCase();
        boolean hasNotNull = upper.matches(".*\\bNOT\\s+NULL\\b.*");
        boolean hasExplicitNull = upper.matches(".*\\bNULL\\b.*") && !hasNotNull;
        Boolean notNull = hasNotNull ? Boolean.TRUE : (hasExplicitNull ? Boolean.FALSE : null);
        String defaultExpr = col.getDefaultExpr() == null ? null : col.getDefaultExpr().toString();
        if (col.isPrimaryKey() || upper.contains(" PRIMARY KEY") || upper.contains(" UNIQUE")) {
            warn(out, "MODIFY 带主键/唯一约束属性，约束变更不在转换范围，已忽略约束部分");
        }
        out.getStatements().addAll(dialect.alterColumnStatements(table, name, type, notNull, defaultExpr));
        String comment = commentText(col);
        if (comment != null) {
            out.getStatements().add(dialect.commentOnColumn(table, name, comment));
        }
    }

    /** 取列注释清洗后的文本；无注释/空注释返回 null。Druid 对 MySQL MODIFY 的 COMMENT 偶有解析进 toString 的情况，做兜底正则。 */
    private String commentText(SQLColumnDefinition col) {
        String raw = col.getComment() == null ? null : col.getComment().toString();
        if (raw == null) {
            Matcher m = Pattern.compile("(?i)\\bCOMMENT\\s+['\"]([^'\"]*)['\"]").matcher(col.toString());
            if (m.find()) {
                raw = m.group(1);
            }
        }
        if (raw == null) {
            return null;
        }
        String cleaned = raw
                .replaceFirst("^\\s*[<>!=]+\\s*", "") // 防 Druid 把 COMMENT 'xxx' 解析成 > 'xxx'
                .replaceAll("^['\"]|['\"]$", "")
                .trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    // ==================== CREATE INDEX ====================

    /** 独立 CREATE [UNIQUE] INDEX 语句：剥掉 MySQL 特有的 USING BTREE 等，按目标方言重排标识符。 */
    private void convertCreateIndex(SQLCreateIndexStatement ci, Dialect dialect, ConvertResult out) {
        if (ci.getTable() == null || ci.getName() == null) {
            warn(out, "CREATE INDEX 缺少索引名或表名，已跳过：" + ci.toString());
            return;
        }
        String table = dialect.quote(AbstractDialect.unquote(
                AbstractDialect.simpleName(((SQLExprTableSource) ci.getTable()).getExpr())));
        List<String> cols = new ArrayList<>();
        if (ci.getItems() != null) {
            for (SQLSelectOrderByItem it : ci.getItems()) {
                if (it == null || it.getExpr() == null) {
                    continue;
                }
                cols.add(AbstractDialect.unquote(it.getExpr().toString()));
            }
        }
        out.getStatements().add(createIndexStatement(
                "UNIQUE".equalsIgnoreCase(ci.getType()), ci.getName(), table, cols, dialect));
    }

    /** 组装 CREATE [UNIQUE] INDEX：索引名/列名走 dialect.quote（保留字引号 + 大写归一）。 */
    private String createIndexStatement(boolean unique, SQLName name, String table,
                                        List<?> cols, Dialect dialect) {
        String idxName = dialect.quote(AbstractDialect.unquote(AbstractDialect.simpleName(name)));
        return (unique ? "CREATE UNIQUE INDEX " : "CREATE INDEX ") + idxName
                + " ON " + table + " (" + colList(cols, dialect) + ")";
    }

    // ==================== COMMENT ON 语句 ====================

    /**
     * 处理独立的 COMMENT ON 语句（达梦 / 神通均支持：COMMENT ON COLUMN/CREATE INDEX 等）。
     * MySQL 中并不存在这种写法——Druid 在解析「ALTER TABLE ... ADD COLUMN ... COMMENT 'xxx'」
     * 或「CREATE TABLE ... (col INT COMMENT 'xxx', ...) COMMENT='yyy'」时，会把列/表注释
     * 单独抽出为 {@link SQLCommentStatement}；必须按方言重新组装，否则注释信息被吞掉。
     */
    private void convertComment(SQLCommentStatement c, Dialect dialect, ConvertResult out) {
        SQLExprTableSource on = c.getOn();
        if (on == null) {
            warn(out, "COMMENT ON 缺少 ON 目标，已跳过：" + c.toString());
            return;
        }
        // 注释文本：Druid 输出自带首尾单引号 + 内部 '' 转义。先还原成原文，再由 dialect.commentOnXxx 重新 esc
        String comment = c.getComment() == null ? "" : c.getComment().toString()
                .replaceAll("^'|'$", "").replace("''", "'");
        // 表/列名可能带反引号（MySQL 风格），先统一剥掉再交给 dialect.quote 加保留字引号
        String onText = AbstractDialect.unquote(on.toString());
        switch (c.getType()) {
            case TABLE: {
                out.getStatements().add(dialect.commentOnTable(dialect.quote(onText), comment));
                break;
            }
            case COLUMN: {
                int dot = onText.lastIndexOf('.');
                if (dot < 0) {
                    warn(out, "COMMENT ON COLUMN 缺少表限定，已跳过：" + c.toString());
                    return;
                }
                String table = onText.substring(0, dot);
                String column = onText.substring(dot + 1);
                out.getStatements().add(dialect.commentOnColumn(dialect.quote(table), dialect.quote(column), comment));
                break;
            }
            default:
                warn(out, "COMMENT 类型 " + c.getType() + " 暂不支持，已跳过：" + c.toString());
        }
    }

    // ==================== 简单 DML 直通 ====================

    private void passThrough(SQLStatement st, ConvertResult out) {
        String text = st.toString().toUpperCase();
        if (st instanceof SQLInsertStatement) {
            SQLInsertStatement ins = (SQLInsertStatement) st;
            if (ins.getValuesList() != null && ins.getValuesList().size() > 1) {
                warn(out, "多值 INSERT 目标库可能不支持，若不支持请手工拆成多条");
            }
            // MySQL 特有语法：检测时不分前后空格（Druid toString 常带换行）
            if (text.contains("ON DUPLICATE KEY")) {
                warn(out, "INSERT ... ON DUPLICATE KEY UPDATE 不在本次支持范围，已按原样输出");
            }
            if (text.contains("REPLACE ")) {
                warn(out, "REPLACE INTO 不在本次支持范围，已按原样输出");
            }
        } else if (st instanceof SQLUpdateStatement) {
            if (text.contains("JOIN") || text.contains("LIMIT")) {
                warn(out, "UPDATE 含 JOIN/LIMIT（MySQL 特有语法），已按原样输出，请人工核对");
            }
        } else if (st instanceof SQLDeleteStatement) {
            if (text.contains("LIMIT")) {
                warn(out, "DELETE 含 LIMIT（MySQL 特有语法），已按原样输出，请人工核对");
            }
        } else if (st instanceof SQLDropTableStatement) {
            if (text.contains("IF EXISTS")) {
                warn(out, "DROP TABLE IF EXISTS：目标库是否支持 IF EXISTS 需实测");
            }
        }
        out.getStatements().add(st.toString());
    }

    // ==================== 工具方法 ====================

    private void warn(ConvertResult out, String msg) {
        out.getWarnings().add(msg);
    }

    private String colList(List<?> cols, Dialect dialect) {
        List<String> names = new ArrayList<>();
        if (cols != null) {
            for (Object e : cols) {
                names.add(dialect.quote(AbstractDialect.unquote(e.toString())));
            }
        }
        return String.join(", ", names);
    }

    /** 列名归一化键：去引用、取简单名、转小写，用于列级与表级主键列集合的比对。 */
    private String colKey(Object nameObj) {
        return AbstractDialect.unquote(AbstractDialect.simpleName(nameObj)).toLowerCase();
    }

    /** Druid 把列级约束（NOT NULL / UNIQUE / CHECK 等）放在 getConstraints() 列表里，按关键字遍历判断。 */
    private boolean hasConstraint(SQLColumnDefinition col, String keyword) {
        // toString 全文匹配关键字，避免依赖 Druid 1.2.x 不稳定的 getConstraints() API
        return col.toString().toUpperCase().matches(".*\\b" + keyword + "\\b.*");
    }

    private String extractIndexName(String rest) {
        String before = rest.replaceAll("\\(.*$", "").trim();
        String[] tokens = before.split("\\s+");
        return tokens.length > 0 ? tokens[0] : "idx_" + System.currentTimeMillis();
    }

    private String extractColumns(String rest) {
        Matcher m = PAREN_COLS.matcher(rest);
        if (!m.find()) {
            return "";
        }
        // 去掉 USING BTREE / ASC / DESC 等 MySQL 特有修饰
        return m.group(1).trim().replaceAll("(?i)\\s+(USING\\s+\\w+|ASC|DESC)\\b", "");
    }
}
