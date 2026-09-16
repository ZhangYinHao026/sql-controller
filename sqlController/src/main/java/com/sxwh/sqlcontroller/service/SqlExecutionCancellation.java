package com.sxwh.sqlcontroller.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SQL 执行取消中心。
 * <p>
 * 同一个执行请求（requestId）在 Controller 里逐库串行执行，任意时刻每个请求至多有一个活跃 JDBC Statement。
 * 取消由独立 HTTP 线程发起：请求进来后打上“已取消”标记，并跨线程调用活跃语句的
 * {@link Statement#cancel()} 中止数据库端查询；驱动不支持 cancel（如神通）时退化为关闭连接强杀。
 * <p>
 * 标记（cancelled）在请求彻底结束后才清除：即便取消瞬间语句恰好不在执行（库与库之间），
 * 主线程也会在下一轮循环前发现标记而终止，不再执行剩余库。
 */
@Component
public class SqlExecutionCancellation {
    private static final Logger log = LoggerFactory.getLogger(SqlExecutionCancellation.class);

    /** 活跃语句句柄：携带关联 Connection，用于驱动不支持 cancel() 时关闭连接兜底。 */
    private static final class Handle {
        final Statement statement;
        final Connection connection;

        Handle(Statement statement, Connection connection) {
            this.statement = statement;
            this.connection = connection;
        }
    }

    /** requestId -> 当前活跃语句（执行线程注册，取消线程读取）。 */
    private final Map<String, Handle> active = new ConcurrentHashMap<>();
    /** 已收到取消请求的 requestId，请求结束前持续生效，用于终止后续库执行。 */
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    /** 兜底清理：取消落在语句间隙（无活跃句柄）时延迟移除标记，防止注册表泄漏。 */
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "sql-cancel-sweeper");
        thread.setDaemon(true);
        return thread;
    });
    /** 当前请求 ID：由 Controller 在串行循环前绑定，执行服务据此自动注册语句。 */
    private final ThreadLocal<String> currentRequest = new ThreadLocal<>();

    public void bind(String requestId) {
        currentRequest.set(requestId);
    }

    /** 注册当前线程正在执行的语句；无绑定 requestId（后台元数据查询等）时不注册。 */
    public void track(Statement statement) {
        String requestId = currentRequest.get();
        if (requestId == null || statement == null) return;
        Connection connection = null;
        try {
            connection = statement.getConnection();
        } catch (Exception ignored) {
            // 拿不到连接引用仍注册语句本身，cancel() 失败时仅记录告警
        }
        active.put(requestId, new Handle(statement, connection));
    }

    public void untrack() {
        String requestId = currentRequest.get();
        if (requestId != null) active.remove(requestId);
    }

    /** 取消线程入口：标记取消并中止活跃语句（若存在）。幂等，重复取消无害。 */
    public void requestCancel(String requestId) {
        if (requestId == null || requestId.isEmpty()) return;
        Handle handle = active.get(requestId);
        if (handle == null) {
            // 语句间隙或请求已结束：仍打标记（阻止库与库之间继续执行），并延迟清理防止泄漏
            log.info("[SQL-CANCEL] requestId={} 当前无活跃语句，仅打取消标记（语句间隙或请求刚结束）", requestId);
            cancelled.add(requestId);
            sweeper.schedule(() -> {
                cancelled.remove(requestId);
                active.remove(requestId);
            }, 2, TimeUnit.MINUTES);
            return;
        }
        cancelled.add(requestId);
        try {
            handle.statement.cancel();
            log.info("[SQL-CANCEL] requestId={} 已发送 statement.cancel()", requestId);
        } catch (Exception exception) {
            log.warn("[SQL-CANCEL] requestId={} statement.cancel() 失败({})，退化为关闭连接强杀",
                    requestId, exception.getMessage());
            if (handle.connection != null) {
                try {
                    handle.connection.close();
                } catch (Exception ignored) {
                    // 连接关闭异常忽略：主线程语句会因连接失效而中断
                }
            }
        }
    }

    public boolean isCancelled(String requestId) {
        return requestId != null && cancelled.contains(requestId);
    }

    /** 请求彻底结束后清理注册与标记（Controller finally 调用），防止泄漏。 */
    public void cleanup(String requestId) {
        if (requestId == null) return;
        active.remove(requestId);
        cancelled.remove(requestId);
    }

    public void unbind() {
        currentRequest.remove();
    }
}
