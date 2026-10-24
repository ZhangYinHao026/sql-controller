package com.sxwh.sqlcontroller.controller;

import com.sxwh.sqlcontroller.model.OnlineConnection;
import com.sxwh.sqlcontroller.model.SchemaSnapshot;
import com.sxwh.sqlcontroller.model.SchemaSnapshotRequest;
import com.sxwh.sqlcontroller.service.OnlineConnectionService;
import com.sxwh.sqlcontroller.service.OnlineQueryService;
import com.sxwh.sqlcontroller.service.OnlineRegionCatalogService;
import com.sxwh.sqlcontroller.service.OnlineSchemaService;
import com.sxwh.sqlcontroller.service.OnlineWriteService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 线上环境接口：区域配置 + 只读查询（小助手代理）+ 写入（各省 updateSql）。
 *
 * <p>与本地的 {@code /api/database/*}、{@code /api/sql/execute} 完全隔离：
 * 线上连接的 ID 空间独立，前端以 {@code online:<id>} 区分，避免与本地连接 id 相撞。</p>
 */
@RestController
@RequestMapping("/api/online")
@CrossOrigin
public class OnlineController {
    private final OnlineConnectionService connections;
    private final OnlineRegionCatalogService catalog;
    private final OnlineQueryService query;
    private final OnlineWriteService write;
    private final OnlineSchemaService onlineSchema;

    public OnlineController(OnlineConnectionService connections, OnlineRegionCatalogService catalog,
                            OnlineQueryService query, OnlineWriteService write, OnlineSchemaService onlineSchema) {
        this.connections = connections;
        this.catalog = catalog;
        this.query = query;
        this.write = write;
        this.onlineSchema = onlineSchema;
    }

    /** 服务地址等只读信息（不含任何密钥）。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return connections.status();
    }

    @GetMapping("/connections")
    public List<OnlineConnection> list(@RequestParam(required = false) String keyword) {
        return connections.list(keyword);
    }

    @PostMapping("/connections")
    public OnlineConnection add(@RequestBody OnlineConnection body) {
        return connections.save(null, body);
    }

    @PutMapping("/connections/{id}")
    public OnlineConnection edit(@PathVariable Long id, @RequestBody OnlineConnection body) {
        return connections.save(id, body);
    }

    @DeleteMapping("/connections/{id}")
    public void delete(@PathVariable Long id) {
        connections.delete(id);
    }

    /** 连通性测试：发一条不带分号的 SELECT。 */
    @PostMapping("/connections/{id}/test")
    public Map<String, Object> test(@PathVariable Long id) {
        return testConnection(connections.get(id));
    }

    /** 测试尚未保存的配置。 */
    @PostMapping("/connections/test-draft")
    public Map<String, Object> testDraft(@RequestBody OnlineConnection body) {
        return testConnection(body);
    }

    private Map<String, Object> testConnection(OnlineConnection c) {
        long started = System.currentTimeMillis();
        try {
            OnlineQueryService.Rows rows = query.selectRows(c, "SELECT 1 AS online_ok", 1, 1);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("success", true);
            ok.put("durationMs", System.currentTimeMillis() - started);
            ok.put("dataTime", rows.dataTime);
            ok.put("columns", rows.columns);
            ok.put("rows", rows.rows);
            return ok;
        } catch (Exception e) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("success", false);
            fail.put("durationMs", System.currentTimeMillis() - started);
            fail.put("errorMessage", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return fail;
        }
    }

    /** 区域清单（只含 type=j 的省）。 */
    @GetMapping("/regions")
    public Map<String, Object> regions(@RequestParam(required = false) String ver) {
        return catalog.regions(ver);
    }

    /**
     * 线上结构快照（字段对比用）：与本地 {@code /api/diff/schema-snapshot} 同构，
     * 但元数据经小助手只读抓取（按方言选字典：MySQL=information_schema，达梦/神通=all_tab_columns 系）。
     * req.tables 非空 = 执行补丁后的局部刷新（只抓这些表，跳过缓存）。
     */
    @PostMapping("/schema-snapshot")
    public List<SchemaSnapshot> schemaSnapshot(@RequestBody SchemaSnapshotRequest req) {
        boolean force = req != null && req.isForce();
        List<Long> ids = req == null ? null : req.getConnectionIds();
        List<String> tables = req == null ? null : req.getTables();
        return onlineSchema.snapshots(ids, force, tables);
    }

    /** 一键导入该版本下全部 java 区域（已存在的跳过）。 */
    @PostMapping("/import-regions")
    public Map<String, Object> importRegions(@RequestParam(required = false) String ver) {
        return connections.importRegions(ver);
    }

    /**
     * 多区域并行查询。请求：{@code {connectionIds:[1,2], sql, page, pageSize, useCache, cacheTime}}；
     * 响应与 {@code /api/sql/execute} 同构（results[] 按入参顺序返回）。
     */
    @PostMapping("/query")
    public Map<String, Object> doQuery(@RequestBody Map<String, Object> body) {
        List<Long> ids = ids(body.get("connectionIds"));
        if (ids.isEmpty()) throw new IllegalArgumentException("至少选择一个线上区域");
        String sql = body.get("sql") == null ? null : String.valueOf(body.get("sql"));
        if (sql == null || sql.trim().isEmpty()) throw new IllegalArgumentException("SQL 不能为空");
        List<OnlineConnection> targets = new ArrayList<>();
        for (Long id : ids) targets.add(connections.get(id));

        List<Map<String, Object>> results = query.queryAll(targets, sql,
                toInt(body.get("page"), 1),
                toInt(body.get("pageSize"), null),
                Boolean.TRUE.equals(body.get("useCache")) || "true".equals(String.valueOf(body.get("useCache"))),
                toInt(body.get("cacheTime"), 120));
        return envelope(ids.size(), results);
    }

    /**
     * 写入（单区域、单语句、必须二次确认）。
     * 请求：{@code {connectionId, sql, confirmed:true}}；响应与查询同构（results 只有一项）。
     */
    @PostMapping("/write")
    public Map<String, Object> doWrite(@RequestBody Map<String, Object> body) {
        Long id = toLong(body.get("connectionId"));
        if (id == null) throw new IllegalArgumentException("写入必须指定唯一区域");
        Object ids = body.get("connectionIds");
        if (ids != null && ids(ids).size() > 1) throw new IllegalArgumentException("写入只允许选择 1 个区域");
        String sql = body.get("sql") == null ? null : String.valueOf(body.get("sql"));
        if (sql == null || sql.trim().isEmpty()) throw new IllegalArgumentException("SQL 不能为空");
        Object confirmed = body.get("confirmed");
        boolean ok = Boolean.TRUE.equals(confirmed) || "true".equals(String.valueOf(confirmed));
        if (!ok) throw new IllegalStateException("写操作必须确认");

        Map<String, Object> item = write.write(connections.get(id), sql);
        List<Map<String, Object>> results = new ArrayList<>();
        results.add(item);
        return envelope(1, results);
    }

    private Map<String, Object> envelope(int total, List<Map<String, Object>> results) {
        int success = 0;
        for (Map<String, Object> r : results) if ("SUCCESS".equals(r.get("status"))) success++;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", UUID.randomUUID().toString());
        response.put("executionMode", "ONLINE");
        response.put("totalConnections", total);
        response.put("successConnections", success);
        response.put("failedConnections", Math.max(0, total - success));
        response.put("results", results);
        return response;
    }

    private static List<Long> ids(Object value) {
        List<Long> out = new ArrayList<>();
        if (value instanceof List) {
            for (Object o : (List<?>) value) {
                Long id = toLong(o);
                if (id != null && !out.contains(id)) out.add(id);
            }
        }
        return out;
    }

    private static Long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return v == null ? null : Long.valueOf(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer toInt(Object v, Integer def) {
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return v == null ? def : (int) Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }
}
