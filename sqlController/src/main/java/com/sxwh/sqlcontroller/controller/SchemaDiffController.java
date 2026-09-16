package com.sxwh.sqlcontroller.controller;

import com.sxwh.sqlcontroller.model.SchemaSnapshot;
import com.sxwh.sqlcontroller.model.SchemaSnapshotRequest;
import com.sxwh.sqlcontroller.service.SchemaService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 字段对比数据源接口：批量抓取基准库 + 比对库的结构快照。
 *
 * <p>前端调用：POST /api/diff/schema-snapshot
 * body: { "connectionIds": [1, 2, 3], "force": false }
 * 返回与请求同序的 {@link SchemaSnapshot} 列表（单库失败 ok=false 并带 error，不拖累其它库）。
 */
@RestController
@RequestMapping("/api/diff")
@CrossOrigin
public class SchemaDiffController {

    private final SchemaService service;

    public SchemaDiffController(SchemaService service) {
        this.service = service;
    }

    /**
     * 一次抓取多个连接的结构快照（基准库 + 比对库），force=true 跳过缓存；
     * req.tables 非空时退化为「仅抓这些表」（执行补丁后的局部刷新，忽略缓存）。
     */
    @PostMapping("/schema-snapshot")
    public List<SchemaSnapshot> snapshot(@RequestBody SchemaSnapshotRequest req) {
        boolean force = req != null && req.isForce();
        List<Long> ids = req == null ? null : req.getConnectionIds();
        List<String> tables = req == null ? null : req.getTables();
        if (tables != null && !tables.isEmpty()) {
            // 局部刷新：目标库已执行补丁，整库缓存必然过期 → 强制按表真查
            return service.snapshots(ids, true, tables);
        }
        return service.snapshots(ids, force);
    }
}
