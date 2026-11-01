package com.sxwh.sqlcontroller.util;

import com.sxwh.sqlcontroller.model.ColumnInfo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Locale;

/**
 * 表浏览写入口的值编解码：JSON 值 ⇄ JDBC 参数 / SQL 字面量。
 *
 * <p>两条执行链路对值的要求不同，所以这里分开：
 * <ul>
 *   <li>本地直连走 {@link #coerce(ColumnInfo, Object)} → {@code PreparedStatement} 绑定 {@code ?}；
 *       按列类型把 JSON 的字符串/数字转成驱动能接受的具体类型（达梦/神通对隐式转换比 MySQL 严格）。</li>
 *   <li>线上小助手不支持占位符，走 {@link #literal(ColumnInfo, Object)} 内联字面量，
 *       字符串按 MySQL 规则转义（反斜杠 + 单引号）。</li>
 * </ul>
 */
public final class DbValueCodec {

    private DbValueCodec() {
    }

    /** JSON 值 → JDBC 参数（按列类型归一）。空串在非字符串列上视为 NULL。 */
    public static Object coerce(ColumnInfo column, Object raw) {
        if (raw == null) {
            return null;
        }
        String type = typeOf(column);
        if (isInteger(type)) {
            if (raw instanceof Number) return ((Number) raw).longValue();
            String text = String.valueOf(raw).trim();
            if (text.isEmpty()) return null;
            try {
                return Long.valueOf(new BigDecimal(text).toBigInteger().toString());
            } catch (NumberFormatException e) {
                throw invalid(column, raw, "整数");
            }
        }
        if (isDecimal(type)) {
            if (raw instanceof BigDecimal) return raw;
            if (raw instanceof Number) return new BigDecimal(String.valueOf(raw));
            String text = String.valueOf(raw).trim();
            if (text.isEmpty()) return null;
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException e) {
                throw invalid(column, raw, "数字");
            }
        }
        if (isBoolean(type)) {
            if (raw instanceof Boolean) return raw;
            String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
            if (text.isEmpty()) return null;
            if ("1".equals(text) || "true".equals(text) || "y".equals(text) || "yes".equals(text)) return Boolean.TRUE;
            if ("0".equals(text) || "false".equals(text) || "n".equals(text) || "no".equals(text)) return Boolean.FALSE;
            throw invalid(column, raw, "布尔值(0/1)");
        }
        if (isTemporal(type)) {
            if (raw instanceof Timestamp) return raw;
            String text = String.valueOf(raw).trim();
            if (text.isEmpty()) return null;
            try {
                return toTimestamp(text);
            } catch (RuntimeException e) {
                throw invalid(column, raw, "日期时间(yyyy-MM-dd HH:mm:ss)");
            }
        }
        return String.valueOf(raw);
    }

    /** 值 → SQL 字面量（线上写入内联用，按 MySQL 规则转义）。先按列类型归一，再转义。 */
    public static String literal(ColumnInfo column, Object raw) {
        return literal(column, raw, true);
    }

    /**
     * 值 → SQL 字面量。
     *
     * @param escapeBackslash 是否需要把反斜杠转义成双反斜杠。MySQL 需要；
     *                        达梦/神通遵循标准 SQL（反斜杠是普通字符），转义反而会把数据写坏
     */
    public static String literal(ColumnInfo column, Object raw, boolean escapeBackslash) {
        Object value = coerce(column, raw);
        if (value == null) {
            return "NULL";
        }
        String type = typeOf(column);
        if (isInteger(type) || isDecimal(type)) {
            return String.valueOf(value);
        }
        if (isBoolean(type)) {
            return Boolean.TRUE.equals(value) ? "1" : "0";
        }
        if (value instanceof Timestamp) {
            // Timestamp.toString() 恒带 ".0" 尾巴，只去末尾的零，避免把毫秒位截断错
            return "'" + value.toString().replaceFirst("\\.0+$", "") + "'";
        }
        if (isBlob(type)) {
            // 二进制大字段不支持从网格写入：直接拒绝，避免生成半截字面量把库写坏
            throw new IllegalArgumentException("字段 " + column.getName() + " 是二进制/大文本类型，暂不支持在网格中编辑");
        }
        return "'" + escape(String.valueOf(value), escapeBackslash) + "'";
    }

    /** MySQL 字符串字面量转义：反斜杠优先，再转单引号与控制字符。 */
    public static String escape(String value) {
        return escape(value, true);
    }

    /** 按方言转义字符串字面量。 */
    public static String escape(String value, boolean escapeBackslash) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'') {
                out.append("''");
                continue;
            }
            if (escapeBackslash && c == '\\') {
                out.append("\\\\");
                continue;
            }
            if (escapeBackslash && c == '\n') {
                out.append("\\n");
                continue;
            }
            if (escapeBackslash && c == '\r') {
                out.append("\\r");
                continue;
            }
            if (escapeBackslash && c == '\0') {
                out.append("\\0");
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 该列是否允许在网格里直接编辑（二进制/大对象暂不支持）。 */
    public static boolean editable(ColumnInfo column) {
        String type = typeOf(column);
        return !isBlob(type);
    }

    /** 类型名小写后是否属于某种族（供服务层判断可编辑性）。 */
    public static boolean isNumeric(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return isInteger(t) || isDecimal(t);
    }

    public static boolean isTemporal(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return t.contains("date") || t.contains("time") || t.contains("year");
    }

    /** 二进制列（无法用文本安全表达）。TEXT/CLOB 按字符串编辑，不在此列。 */
    public static boolean isBlob(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return t.contains("blob") || t.contains("binary") || t.contains("image")
                || t.contains("bytea") || t.equals("raw");
    }

    private static boolean isInteger(String type) {
        String t = type == null ? "" : type;
        return t.contains("int") || t.contains("serial") || t.equals("number") || t.equals("bigserial");
    }

    private static boolean isDecimal(String type) {
        String t = type == null ? "" : type;
        return t.contains("decimal") || t.contains("numeric") || t.contains("number")
                || t.contains("float") || t.contains("double") || t.contains("real") || t.contains("money");
    }

    private static boolean isBoolean(String type) {
        String t = type == null ? "" : type;
        return t.equals("bit") || t.contains("bool");
    }

    private static String typeOf(ColumnInfo column) {
        return column == null || column.getType() == null ? "" : column.getType().trim().toLowerCase(Locale.ROOT);
    }

    private static Timestamp toTimestamp(String text) {
        String value = text.trim().replace('T', ' ');
        if (value.endsWith("Z")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.length() == 10) {
            value = value + " 00:00:00";
        } else if (value.length() == 16) {
            value = value + ":00";
        }
        if (value.length() > 23) {
            value = value.substring(0, 23);
        }
        return Timestamp.valueOf(value);
    }

    private static IllegalArgumentException invalid(ColumnInfo column, Object raw, String expected) {
        return new IllegalArgumentException("字段 " + (column == null ? "" : column.getName())
                + " 的值不是合法" + expected + "：" + raw);
    }
}
