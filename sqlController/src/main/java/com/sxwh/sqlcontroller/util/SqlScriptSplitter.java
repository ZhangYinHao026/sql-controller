package com.sxwh.sqlcontroller.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 将一段 SQL 脚本按语句边界拆分为多条可独立执行的语句。
 *
 * <p>适用场景：达梦（DM8）/神通（Oscar）等 JDBC 驱动没有 MySQL {@code allowMultiQueries}
 * 那样的驱动级多语句支持。当用户在编辑器一次粘贴多语句脚本时，由服务端按语句边界拆分后逐条执行。</p>
 *
 * <p>拆分安全性（保证不会把一条语句拆烂）：</p>
 * <ul>
 *   <li>单引号字符串（含 ''、\' 转义）、双引号/反引号标识符内的分号不参与拆分；</li>
 *   <li>-- 行注释、/* *&#47; 块注释内的分号不参与拆分；</li>
 *   <li>DECLARE 变量声明段与 BEGIN..END 块嵌套期间的裸分号不参与拆分（支持匿名块、存储过程/函数/触发器/包定义）；</li>
 *   <li>END IF / END LOOP / END CASE / END WHILE / END FOR 等复合语句结尾不会误减块深度；</li>
 *   <li>SELECT CASE ... END 表达式的 END 不会误减块深度（其后续通常跟 FROM/AS/, 等而非语句结束符）；</li>
 *   <li>行首 '/'（Oracle/达梦 PL/SQL 执行符）作为块结束标记参与拆分。</li>
 * </ul>
 *
 * <p>输出约定：每条语句已去除首尾空白、不含末尾分号；空段与纯注释段会被丢弃。</p>
 */
public final class SqlScriptSplitter {

    /** 复合语句的 END 后缀：END IF / END LOOP / END CASE / END WHILE / END FOR，不匹配块 BEGIN。 */
    private static final String[] COMPOUND_END_WORDS = {"IF", "LOOP", "CASE", "WHILE", "FOR"};

    private SqlScriptSplitter() {
    }

    public static List<String> split(String script) {
        return new Scanner(script == null ? "" : script).scan();
    }

    /** 单次扫描状态机。 */
    private static final class Scanner {
        private final String script;
        private final int length;
        private final List<String> statements = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();
        private int index = 0;

        private boolean hasCode = false; // 当前段是否包含实际代码（注释不算）
        private boolean lineStart = true; // 自本行行首起只出现过空白
        private boolean inDeclare = false; // 处于 DECLARE 变量声明段（匿名块）
        private int blockDepth = 0; // BEGIN..END 嵌套深度
        // 过程对象保护：CREATE [OR REPLACE] PROCEDURE/FUNCTION/TRIGGER/PACKAGE BODY ... IS/AS
        // 之后 BEGIN 之前的声明区没有 DECLARE 关键字，期间的分号同样不能拆分。
        private boolean createSeen = false;
        private boolean createObject = false;
        private boolean sawPackage = false;
        private boolean declarationProtected = false;

        private Scanner(String script) {
            this.script = script;
            this.length = script.length();
        }

        List<String> scan() {
            while (index < length) {
                char c = script.charAt(index);
                char next = index + 1 < length ? script.charAt(index + 1) : '\0';

                if (c == '-' && next == '-') {
                    lineComment();
                } else if (c == '/' && next == '*') {
                    blockComment();
                } else if (c == '\'' || c == '"' || c == '`') {
                    quoted(c);
                } else if (lineStart && c == '/' && (next == '\0' || Character.isWhitespace(next))) {
                    slashTerminator();
                } else if (Character.isLetter(c) || c == '_') {
                    word();
                } else if (c == ';') {
                    semicolon();
                } else {
                    plain(c);
                }
            }
            flush();
            return statements;
        }

        /** -- 行注释：整段保留，注释内容不算代码。 */
        private void lineComment() {
            current.append(script, index, index + 2);
            index += 2;
            while (index < length && script.charAt(index) != '\n' && script.charAt(index) != '\r') {
                current.append(script.charAt(index));
                index++;
            }
            // 换行符交给 plain() 处理以更新 lineStart
        }

        /** /* 块注释：整段保留。 */
        private void blockComment() {
            int end = script.indexOf("*/", index + 2);
            if (end < 0) {
                current.append(script, index, length);
                index = length;
            } else {
                current.append(script, index, end + 2);
                index = end + 2;
            }
        }

        /** 引号包裹内容（字符串/标识符）：保留原文，含转义处理。 */
        private void quoted(char quote) {
            current.append(quote);
            index++;
            while (index < length) {
                char q = script.charAt(index);
                current.append(q);
                if (q == quote) {
                    if (index + 1 < length && script.charAt(index + 1) == quote) { // '' / "" 转义
                        current.append(quote);
                        index += 2;
                        continue;
                    }
                    index++;
                    break;
                }
                if (q == '\\' && index + 1 < length) { // \' 等转义
                    current.append(script.charAt(index + 1));
                    index += 2;
                    continue;
                }
                index++;
            }
            hasCode = true;
            lineStart = false;
        }

        /** 行首 '/'：PL/SQL 执行符，结束当前语句。 */
        private void slashTerminator() {
            flush();
            while (index < length && script.charAt(index) != '\n' && script.charAt(index) != '\r') index++;
            lineStart = true;
        }

        /** 单词：识别 BEGIN / END / DECLARE 维护块状态。 */
        private void word() {
            int start = index;
            while (index < length && isWordPart(script.charAt(index))) index++;
            String word = script.substring(start, index);
            current.append(word);
            hasCode = true;
            lineStart = false;

            String upper = word.toUpperCase(Locale.ROOT);
            // 过程对象上下文识别（仅对当前段以 CREATE 开头时生效）
            if ("CREATE".equals(upper)) {
                createSeen = true;
            } else if (createSeen) {
                if ("PROCEDURE".equals(upper) || "FUNCTION".equals(upper) || "TRIGGER".equals(upper)) {
                    createObject = true;
                } else if ("PACKAGE".equals(upper)) {
                    sawPackage = true;
                } else if ("BODY".equals(upper) && sawPackage) {
                    createObject = true;
                } else if (("IS".equals(upper) || "AS".equals(upper)) && createObject && blockDepth == 0 && !inDeclare) {
                    declarationProtected = true; // IS/AS 之后的声明区：分号不切分
                }
            }
            if ("BEGIN".equals(upper)) {
                declarationProtected = false;
                if (inDeclare) {
                    blockDepth = 1;
                    inDeclare = false;
                } else {
                    blockDepth++;
                }
            } else if ("DECLARE".equals(upper)) {
                if (blockDepth == 0) inDeclare = true;
            } else if ("END".equals(upper)) {
                endKeyword();
            }
        }

        /** END 关键词语义：排除复合语句与 CASE 表达式后，才配对块 BEGIN。 */
        private void endKeyword() {
            String nextWord = peekWord();
            if (nextWord != null) {
                for (String suffix : COMPOUND_END_WORDS) {
                    if (suffix.equals(nextWord)) return; // END IF / END LOOP / ...：不减深度
                }
            }
            if (blockDepth == 0) return;
            // 块 END 的典型后续：语句结束符（;/行尾/EOF）或 '/'。若后随 FROM/AS/, 等则视为 CASE 表达式，不减。
            char after = peekNonBlank();
            if (after == '\0' || after == ';' || after == '/' || after == '\n' || after == '\r') {
                blockDepth--;
                if (blockDepth == 0) inDeclare = false;
            }
        }

        /** 窥视下一个单词（跳过空白），无则返回 null。 */
        private String peekWord() {
            int cursor = index;
            while (cursor < length && Character.isWhitespace(script.charAt(cursor))) cursor++;
            if (cursor >= length || !(Character.isLetter(script.charAt(cursor)) || script.charAt(cursor) == '_')) return null;
            int start = cursor;
            while (cursor < length && isWordPart(script.charAt(cursor))) cursor++;
            return script.substring(start, cursor).toUpperCase(Locale.ROOT);
        }

        /** 窥视下一个非空白字符，无则返回 \0。 */
        private char peekNonBlank() {
            int cursor = index;
            while (cursor < length && Character.isWhitespace(script.charAt(cursor))) cursor++;
            return cursor >= length ? '\0' : script.charAt(cursor);
        }

        /** 裸分号：仅块外、非 DECLARE 声明段、非过程对象声明区时作为语句分隔符。 */
        private void semicolon() {
            if (blockDepth == 0 && !inDeclare && !declarationProtected) {
                flush();
            } else {
                current.append(';');
            }
            index++;
            lineStart = false;
        }

        /** 普通字符。 */
        private void plain(char c) {
            current.append(c);
            if (c == '\n' || c == '\r') {
                lineStart = true;
            } else if (!Character.isWhitespace(c)) {
                hasCode = true;
                lineStart = false;
            }
            index++;
        }

        /** 结束当前累积段：去首尾空白，纯注释/空段丢弃；同时重置段级状态。 */
        private void flush() {
            String text = current.toString().trim();
            if (!text.isEmpty() && hasCode) statements.add(text);
            current.setLength(0);
            hasCode = false;
            createSeen = false;
            createObject = false;
            sawPackage = false;
            declarationProtected = false;
        }
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }
}
