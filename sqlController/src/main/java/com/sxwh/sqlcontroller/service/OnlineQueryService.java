package com.sxwh.sqlcontroller.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sxwh.sqlcontroller.config.OnlineProperties;
import com.sxwh.sqlcontroller.model.OnlineConnection;
import com.sxwh.sqlcontroller.util.OnlineSqlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * 线上只读查询：把 SQL 代理到内网「远程查询小助手」({@code POST /LocalTest/ToSel})。
 *
 * <p>实测契约（2026-10-23）：</p>
 * <ul>
 *   <li>表单参数 {@code area / pagesize / pageindex / sql / ver / useCache / cacheTime}；{@code area} 只能单个 → 多区域=多请求；</li>
 *   <li>响应 {@code {success, message, data:"<JSON字符串>", dataTime, dataId}}，内层
 *       {@code {code:200, data:{pagecount, columns:"a,b", ds:[...]}, message}}；</li>
 *   <li><b>SQL 带分号必失败</b> → 由 {@link OnlineSqlGuard} 剥掉；<b>写语句一律被拒</b>；</li>
 *   <li>{@code data} 不一定是 JSON（区域非法时是纯文本），需容错。</li>
 * </ul>
 *
 * <p>对外除了给查询页用的 {@link #queryAll}，还提供 {@link #selectRows}：
 * 后续「字段对比接入线上」可直接用它分页读 information_schema，不必再写一套代理。</p>
 */
@Service
public class OnlineQueryService {
    private static final Logger log = LoggerFactory.getLogger(OnlineQueryService.class);

    private final OnlineProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate http;
    private final ExecutorService pool;

    public OnlineQueryService(OnlineProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Math.max(1, properties.getConnectTimeoutSeconds()) * 1000);
        f.setReadTimeout(Math.max(1, properties.getReadTimeoutSeconds()) * 1000);
        this.http = new RestTemplate(f);
        int n = Math.max(1, properties.getMaxConcurrency());
        this.pool = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "online-query-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    /** 单区域查询，返回与本地 {@code /api/sql/execute} 结果项同构的 Map。 */
    public Map<String, Object> query(OnlineConnection c, String rawSql, Integer page, Integer pageSize,
                                     Boolean useCache, Integer cacheTime) {
        int p = page == null ? 1 : Math.max(1, page);
        int size = properties.normalizePageSize(pageSize);
        long started = System.currentTimeMillis();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("connectionId", c.getId());
        item.put("scope", "online");
        item.put("dbType", "online");
        item.put("areaCode", c.getAreaCode());
        item.put("regionName", c.label());
        item.put("dbDialect", c.getDbType());
        item.put("source", "远程查询小助手");
        try {
            String sql = OnlineSqlGuard.prepareRead(rawSql);
            Rows rows = fetch(c, sql, p, size, Boolean.TRUE.equals(useCache), cacheTime);
            item.put("status", "SUCCESS");
            item.put("columns", rows.columns);
            item.put("rows", rows.rows);
            item.put("total", (long) rows.rows.size());
            item.put("pageCount", rows.pageCount);
            item.put("affectedRows", 0L);
            item.put("dataTime", rows.dataTime);
            item.put("dataId", rows.dataId);
        } catch (Exception e) {
            item.put("status", "FAILED");
            item.put("columns", new ArrayList<String>());
            item.put("rows", new ArrayList<Map<String, Object>>());
            item.put("total", 0L);
            item.put("affectedRows", 0L);
            item.put("errorMessage", message(e));
            log.warn("[online] 查询失败 area={} {}: {}", c.getAreaCode(), c.getRegionName(), message(e));
        }
        item.put("durationMs", System.currentTimeMillis() - started);
        return item;
    }

    /** 多区域并行查询，结果按入参顺序稳定返回；单区域失败不阻断其它区域。 */
    public List<Map<String, Object>> queryAll(List<OnlineConnection> connections, String rawSql, Integer page,
                                              Integer pageSize, Boolean useCache, Integer cacheTime) {
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        for (OnlineConnection c : connections) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> query(c, rawSql, page, pageSize, useCache, cacheTime), pool));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get());
            } catch (Exception e) {
                OnlineConnection c = connections.get(i);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("connectionId", c.getId());
                item.put("scope", "online");
                item.put("dbType", "online");
                item.put("areaCode", c.getAreaCode());
                item.put("regionName", c.label());
                item.put("dbDialect", c.getDbType());
                item.put("status", "FAILED");
                item.put("errorMessage", "并行执行异常：" + message(e));
                item.put("columns", new ArrayList<String>());
                item.put("rows", new ArrayList<Map<String, Object>>());
                item.put("total", 0L);
                results.add(item);
            }
        }
        return results;
    }

    /**
     * 只读取行（供查询页与未来的「线上结构快照」共用）。
     *
     * @param sql 只读单语句，调用方保证（内部仍走守卫）
     */
    public Rows selectRows(OnlineConnection c, String sql, int page, int pageSize) {
        return fetch(c, OnlineSqlGuard.prepareRead(sql), Math.max(1, page), pageSize, false, 120);
    }

    /** 实际发请求 + 双层 JSON 归一化。 */
    private Rows fetch(OnlineConnection c, String sql, int page, int pageSize, boolean useCache, Integer cacheTime) {
        if (c.getAreaCode() == null) throw new IllegalArgumentException("该区域未配置区域码");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("area", String.valueOf(c.getAreaCode()));
        form.add("pagesize", String.valueOf(pageSize));
        form.add("pageindex", String.valueOf(page));
        form.add("sql", sql);
        form.add("ver", OnlineRegionCatalogService.normalizeVer(c.getVer()));
        form.add("useCache", useCache ? "1" : "0");
        form.add("cacheTime", String.valueOf(cacheTime == null ? 120 : Math.max(0, cacheTime)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/x-www-form-urlencoded; charset=UTF-8"));
        headers.set("X-Requested-With", "XMLHttpRequest");
        headers.set(HttpHeaders.REFERER, properties.baseUrl() + "/");
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);

        ResponseEntity<byte[]> resp;
        try {
            resp = http.exchange(properties.baseUrl() + "/LocalTest/ToSel", HttpMethod.POST, entity, byte[].class);
        } catch (Exception e) {
            throw new IllegalStateException("调用小助手失败：" + message(e));
        }
        byte[] body = resp.getBody();
        String text = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        if (text.trim().isEmpty()) throw new IllegalStateException("小助手返回空响应（HTTP " + resp.getStatusCodeValue() + "）");

        Map<String, Object> outer;
        try {
            outer = mapper.readValue(text, LinkedHashMap.class);
        } catch (Exception e) {
            throw new IllegalStateException("小助手返回非 JSON：" + truncate(text));
        }
        if (!truthy(outer.get("success"))) {
            throw new IllegalStateException(str(outer.get("message"), "小助手返回失败"));
        }
        String dataTime = str(outer.get("dataTime"), null);
        String dataId = str(outer.get("dataId"), null);
        Object data = outer.get("data");
        if (data instanceof String) {
            String raw = ((String) data).trim();
            Map<String, Object> inner;
            try {
                inner = mapper.readValue(raw, LinkedHashMap.class);
            } catch (Exception e) {
                // 区域非法等情况：data 是纯文本错误
                throw new IllegalStateException("小助手返回：" + truncate(raw));
            }
            int code = toInt(inner.get("code"), 0);
            if (code != 200) {
                String msg = str(inner.get("msg"), str(inner.get("message"), "小助手执行失败（code=" + code + "）"));
                throw new IllegalStateException(msg);
            }
            Object payload = inner.get("data");
            if (!(payload instanceof Map)) {
                throw new IllegalStateException("小助手返回结构异常：data 不是对象");
            }
            Map<?, ?> dataMap = (Map<?, ?>) payload;
            List<String> columns = splitColumns(dataMap.get("columns"));
            List<Map<String, Object>> rows = toRows(dataMap.get("ds"), columns);
            int pageCount = toInt(dataMap.get("pagecount"), 1);
            return new Rows(columns, rows, Math.max(1, pageCount), dataTime, dataId);
        }
        throw new IllegalStateException("小助手返回结构异常：缺少 data 字段");
    }

    private List<String> splitColumns(Object value) {
        List<String> columns = new ArrayList<>();
        if (value == null) return columns;
        for (String s : String.valueOf(value).split(",")) {
            String name = s.trim();
            if (!name.isEmpty()) columns.add(name);
        }
        return columns;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toRows(Object ds, List<String> columns) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!(ds instanceof List)) return rows;
        for (Object o : (List<Object>) ds) {
            if (o instanceof Map) {
                Map<String, Object> src = (Map<String, Object>) o;
                Map<String, Object> row = new LinkedHashMap<>();
                if (columns.isEmpty()) {
                    row.putAll(src);
                } else {
                    for (String col : columns) {
                        row.put(col, src.containsKey(col) ? src.get(col) : findByIgnoreCase(src, col));
                    }
                }
                rows.add(row);
            } else {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("value", o);
                rows.add(row);
            }
        }
        return rows;
    }

    private Object findByIgnoreCase(Map<String, Object> src, String key) {
        for (Map.Entry<String, Object> e : src.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v == null) return false;
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static int toInt(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v == null) return def;
        try {
            return (int) Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String str(Object v, String def) {
        return v == null ? def : String.valueOf(v);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }

    private static String message(Throwable e) {
        if (e == null) return "未知错误";
        if (e instanceof CompletionException && e.getCause() != null) e = e.getCause();
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    /** 小助手返回的一组行（供查询页与未来的结构快照共用）。 */
    public static final class Rows {
        public final List<String> columns;
        public final List<Map<String, Object>> rows;
        public final int pageCount;
        public final String dataTime;
        public final String dataId;

        public Rows(List<String> columns, List<Map<String, Object>> rows, int pageCount, String dataTime, String dataId) {
            this.columns = columns;
            this.rows = rows;
            this.pageCount = pageCount;
            this.dataTime = dataTime;
            this.dataId = dataId;
        }
    }
}
