package com.sxwh.sqlcontroller.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 脚本参数解析器。
 * #{参数名} 为预编译绑定，${参数名} 为文本替换；旧版 {参数名} 兼容为预编译绑定。
 * 解析时会跳过 SQL 字符串和注释，避免把备注中的内容当作参数。
 */
@Service
public class ScriptParameterService {

    /** 返回参数名列表，兼容既有调用方。 */
    public List<String> parseNames(String sql) {
        List<String> names = new ArrayList<>();
        for (ScriptParameter parameter : parseParameters(sql)) {
            if (!names.contains(parameter.getName())) names.add(parameter.getName());
        }
        return names;
    }

    /** 按首次出现顺序返回参数名称和模式。相同名称且模式不同会分别展示。 */
    public List<ScriptParameter> parseParameters(String sql) {
        if (sql == null) return Collections.emptyList();
        Map<String, ScriptParameter> result = new LinkedHashMap<>();
        for (Token token : tokens(sql)) {
            String key = token.mode + ":" + token.name;
            result.putIfAbsent(key, new ScriptParameter(token.name, token.mode));
        }
        return new ArrayList<>(result.values());
    }

    /**
     * #{参数} 转换为 ? 并通过 PreparedStatement 绑定。
     * ${参数} 直接插入 SQL，与 MyBatis ${} 相同，只应用于受控的列名、排序方向等 SQL 片段。
     */
    public BoundSql bind(String sql, Map<String, Object> params) {
        List<Token> tokens = tokens(sql);
        Map<String, Object> supplied = params == null ? Collections.emptyMap() : params;
        StringBuilder executableSql = new StringBuilder();
        List<Object> values = new ArrayList<>();
        int position = 0;
        for (Token token : tokens) {
            executableSql.append(sql, position, token.start);
            if ("#".equals(token.mode)) {
                executableSql.append('?');
                values.add(supplied.get(token.name));
            } else {
                executableSql.append(String.valueOf(supplied.get(token.name)));
            }
            position = token.end;
        }
        executableSql.append(sql.substring(position));
        return new BoundSql(executableSql.toString(), values, parseParameters(sql));
    }

    /** 从正常 SQL 片段中识别参数，字符串与注释内的文本保持原样。 */
    private List<Token> tokens(String sql) {
        List<Token> result = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'' || current == '"' || current == '`') {
                index = skipQuoted(sql, index, current);
            } else if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                index = skipLineComment(sql, index + 2);
            } else if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                index = skipBlockComment(sql, index + 2);
            } else if (current == '#' && !(index + 1 < sql.length() && sql.charAt(index + 1) == '{')) {
                index = skipLineComment(sql, index + 1);
            } else {
                Token token = tokenAt(sql, index);
                if (token != null) {
                    result.add(token);
                    index = token.end;
                } else {
                    index++;
                }
            }
        }
        return result;
    }

    private Token tokenAt(String sql, int index) {
        String mode = null;
        int nameStart;
        if (sql.startsWith("#{", index)) { mode = "#"; nameStart = index + 2; }
        else if (sql.startsWith("${", index)) { mode = "$"; nameStart = index + 2; }
        // 兼容已有的 {参数} 脚本，按安全的 # 参数执行。
        else if (sql.charAt(index) == '{') { mode = "#"; nameStart = index + 1; }
        else return null;

        int end = sql.indexOf('}', nameStart);
        if (end < 0 || end == nameStart) return null;
        String name = sql.substring(nameStart, end);
        if (!isParameterName(name)) return null;
        return new Token(index, end + 1, mode, name);
    }

    private boolean isParameterName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (!(character == '_' || Character.isLetterOrDigit(character))) return false;
        }
        return true;
    }

    private int skipQuoted(String sql, int start, char quote) {
        int index = start + 1;
        while (index < sql.length()) {
            if (sql.charAt(index) == quote) {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) index += 2;
                else return index + 1;
            } else if (sql.charAt(index) == '\\' && index + 1 < sql.length()) index += 2;
            else index++;
        }
        return index;
    }

    private int skipLineComment(String sql, int index) {
        while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') index++;
        return index;
    }

    private int skipBlockComment(String sql, int index) {
        int end = sql.indexOf("*/", index);
        return end < 0 ? sql.length() : end + 2;
    }

    private static class Token {
        private final int start, end;
        private final String mode, name;
        private Token(int start, int end, String mode, String name) {
            this.start = start; this.end = end; this.mode = mode; this.name = name;
        }
    }

    /** 单个脚本参数的名称和替换模式。 */
    public static class ScriptParameter {
        private final String name, mode;
        public ScriptParameter(String name, String mode) { this.name = name; this.mode = mode; }
        public String getName() { return name; }
        public String getMode() { return mode; }
    }

    /** 可直接交给 JDBC 执行的 SQL，以及 # 参数对应的绑定值。 */
    public static class BoundSql {
        private final String sql;
        private final List<Object> values;
        private final List<ScriptParameter> parameters;
        public BoundSql(String sql, List<Object> values, List<ScriptParameter> parameters) {
            this.sql = sql; this.values = values; this.parameters = parameters;
        }
        public String getSql() { return sql; }
        public List<Object> getValues() { return values; }
        public List<ScriptParameter> getParameters() { return parameters; }
        public List<String> getNames() {
            List<String> names = new ArrayList<>();
            for (ScriptParameter parameter : parameters) if (!names.contains(parameter.getName())) names.add(parameter.getName());
            return names;
        }
    }
}
