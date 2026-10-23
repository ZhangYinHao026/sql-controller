package com.sxwh.sqlcontroller.util;

import java.util.*;

/**
 * 线上环境 SQL 守卫：把用户写的 SQL 归一成「小助手 / 写入接口」能接受的形状。
 *
 * <p>规则只做三件事，且都在服务端强制执行（前端提示仅供参考）：</p>
 * <ol>
 *   <li><b>去尾分号</b>：小助手对带 {@code ;} 的 SQL 一律返回 code:300，因此这里直接剥掉，
 *       不报错、不打扰用户（多语句仍然拒绝，避免静默丢语句）。</li>
 *   <li><b>单语句</b>：解析出多条语句时拒绝，并说明检测到几条。</li>
 *   <li><b>首词白名单</b>：只读通道仅 SELECT/SHOW/DESC/DESCRIBE/EXPLAIN/WITH；
 *       写入通道仅 UPDATE/INSERT/REPLACE（DELETE / DDL 需显式配置开关）。</li>
 * </ol>
 *
 * <p>拆分复用 {@link SqlScriptSplitter}（能正确跳过字符串、标识符、注释、PL/SQL 块内的分号）。</p>
 */
public final class OnlineSqlGuard {

    /** 只读通道允许的首词。 */
    public static final Set<String> READ_HEADS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("SELECT", "SHOW", "DESC", "DESCRIBE", "EXPLAIN", "WITH")));
    /** 写入通道默认允许的首词。 */
    public static final Set<String> WRITE_HEADS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("UPDATE", "INSERT", "REPLACE")));
    /** 仅当 sql-controller.online.write.allow-delete=true 时放行。 */
    public static final Set<String> DELETE_HEADS = Collections.singleton("DELETE");
    /** 仅当 sql-controller.online.write.allow-ddl=true 时放行（后续「结构同步到线上」使用）。 */
    public static final Set<String> DDL_HEADS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME")));

    private OnlineSqlGuard() {
    }

    /** 只读通道准备：去尾分号 + 单语句校验 + 只读白名单。 */
    public static String prepareRead(String raw) {
        String sql = single(raw);
        String head = head(sql);
        if (!READ_HEADS.contains(head)) {
            throw new IllegalArgumentException("线上查询只支持只读语句（" + String.join("/", READ_HEADS)
                    + "），当前是 " + (head.isEmpty() ? "空语句" : head));
        }
        String upper = sql.toUpperCase(Locale.ROOT);
        if (upper.contains("FOR UPDATE") || upper.contains("LOCK IN SHARE MODE")) {
            throw new IllegalArgumentException("线上查询不允许加锁读（FOR UPDATE / LOCK IN SHARE MODE）");
        }
        return sql;
    }

    /**
     * 写入通道准备。
     *
     * <p>DDL（CREATE/ALTER/DROP/TRUNCATE/RENAME）**始终放行**——这是内部开发工具，
     * 「本地结构同步到线上」等场景需要它；风险由前端二次确认承担。</p>
     *
     * @param allowDelete 是否允许 DELETE
     */
    public static String prepareWrite(String raw, boolean allowDelete) {
        String sql = single(raw);
        String head = head(sql);
        boolean ok = WRITE_HEADS.contains(head)
                || DDL_HEADS.contains(head)
                || (allowDelete && DELETE_HEADS.contains(head));
        if (!ok) {
            List<String> allowed = new ArrayList<>(WRITE_HEADS);
            allowed.addAll(DDL_HEADS);
            if (allowDelete) allowed.add("DELETE");
            throw new IllegalArgumentException("线上修改不允许该语句（" + String.join("/", allowed) + "），当前是 "
                    + (head.isEmpty() ? "空语句" : head));
        }
        return sql;
    }

    /**
     * 去尾分号并保证只有一条语句。带分号的单条 SQL 会被静默剥掉末尾分号（不报错）；多条则拒绝。
     */
    public static String single(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("SQL 不能为空");
        List<String> statements = SqlScriptSplitter.split(raw);
        if (statements.isEmpty()) throw new IllegalArgumentException("SQL 不能为空");
        if (statements.size() > 1) {
            String first = head(statements.get(0));
            throw new IllegalArgumentException("线上只支持单条语句（检测到 " + statements.size()
                    + " 条，首条是 " + (first.isEmpty() ? "?" : first) + "），请拆分后逐条执行");
        }
        String sql = statements.get(0).trim();
        // SqlScriptSplitter 已去掉语句间/末尾的分号；这里再兜一次「纯分号结尾」的极端情况（如 ...;)）
        while (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
        if (sql.isEmpty()) throw new IllegalArgumentException("SQL 不能为空");
        return sql;
    }

    /** 取首词（跳过前导空白、注释与开括号）。 */
    public static String head(String sql) {
        if (sql == null) return "";
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == '(' || c == '\uFEFF') {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                break;
            }
        }
        int start = i;
        while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
        return sql.substring(start, i).toUpperCase(Locale.ROOT);
    }
}
