package com.sxwh.sqlcontroller.dialect;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 方言抽象基类：提供公共实现（类型参数处理、保留字引号、注释语句、自增子句）。
 * 子类只需实现 name() / typeMap() / reservedWords()；JDBC 行为由 JdbcPlatform 负责。
 */
public abstract class AbstractDialect implements Dialect {

    /** 各方言的 MySQL→目标 类型映射表（不含 CHAR/VARCHAR/DECIMAL 等带参数类型的公共处理）。 */
    protected abstract Map<String, String> typeMap();

    /** 各方言的保留字表。 */
    protected abstract List<String> reservedWords();

    /** 带参数（长度/精度）的通用类型：由 mapType 公共逻辑处理。 */
    private static final Set<String> PARAM_TYPES = new HashSet<>(Arrays.asList(
            "CHAR", "VARCHAR", "DECIMAL", "NUMERIC", "ENUM", "SET"));

    @Override
    public String mapType(String mysqlType, List<SQLExpr> args) {
        int p = argInt(args, 0);
        int s = argInt(args, 1);
        switch (mysqlType) {
            case "CHAR":
                return "CHAR(" + (p > 0 ? p : 1) + ")";
            case "VARCHAR":
            case "ENUM":
            case "SET":
                // ENUM/SET 目标库无枚举，转 VARCHAR（调用方需补充 warning）
                return "VARCHAR(" + (p > 0 ? p : 255) + ")";
            case "DECIMAL":
            case "NUMERIC":
                return "DECIMAL(" + (p > 0 ? p : 18) + "," + (s >= 0 ? s : 0) + ")";
            default:
                return typeMap().getOrDefault(mysqlType, mysqlType);
        }
    }

    @Override
    public boolean isKnownType(String mysqlType) {
        return PARAM_TYPES.contains(mysqlType) || typeMap().containsKey(mysqlType);
    }

    @Override
    public String quote(String identifier) {
        // 达梦/神通对不带引号的标识符自动折叠为大写存储；方言可声明 uppercaseIdentifiers()=true，
        // 使输出（含保留字引号内 "DATE"）与库内实际大小写一致，避免同一标识符大小写混用导致查不到列。
        String id = identifier == null ? null
                : (uppercaseIdentifiers() ? identifier.toUpperCase() : identifier);
        if (id == null) {
            return null;
        }
        for (String kw : reservedWords()) {
            if (kw.equalsIgnoreCase(id)) {
                return "\"" + id + "\"";
            }
        }
        return id;
    }

    /** 标识符统一大写输出（达梦/神通默认大小写敏感，不带引号的标识符在库内折叠为大写）。 */
    protected boolean uppercaseIdentifiers() {
        return false;
    }

    @Override
    public String identityClause() {
        // 达梦 DM8 用「列类型 + IDENTITY(1,1)」表达自增。
        // 神通 OSCAR 不支持该语法（PG 内核），自增改走伪类型：OscarDialect 覆盖 pseudoTypeIdentity()=true
        // 并在转换侧用 SERIAL/BIGSERIAL 整体替换列类型（见 SqlConvertService.buildColumn）。
        return "IDENTITY(1,1)";
    }

    @Override
    public String commentOnColumn(String table, String column, String comment) {
        return "COMMENT ON COLUMN " + table + "." + column + " IS '" + esc(comment) + "'";
    }

    @Override
    public String commentOnTable(String table, String comment) {
        return "COMMENT ON TABLE " + table + " IS '" + esc(comment) + "'";
    }

    /** 兼容 String / SQLName 两种标识符返回类型，统一取简单名。 */
    public static String simpleName(Object nameObj) {
        if (nameObj == null) {
            return "";
        }
        if (nameObj instanceof SQLName) {
            return ((SQLName) nameObj).getSimpleName();
        }
        return nameObj.toString();
    }

    /** 去掉标识符首尾反引号/双引号。 */
    public static String unquote(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("^`+|`+$", "").replaceAll("^\"+|\"+$", "");
    }

    /** 注释文本转义单引号。 */
    public static String esc(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    private static int argInt(List<SQLExpr> args, int index) {
        if (args == null || args.size() <= index || args.get(index) == null) {
            return -1;
        }
        try {
            String text = args.get(index).toString().replaceAll("[^0-9]", "");
            return text.isEmpty() ? -1 : Integer.parseInt(text);
        } catch (Exception e) {
            return -1;
        }
    }
}
