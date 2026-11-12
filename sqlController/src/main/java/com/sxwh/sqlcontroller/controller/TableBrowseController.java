package com.sxwh.sqlcontroller.controller;

import com.sxwh.sqlcontroller.service.TableBrowseService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表浏览接口（Navicat 式「点表看数据」的只读后端）。
 *
 * <p>与 {@code /api/sql/execute} 的分工：这里是**结构化**入口，服务端负责拼 SQL、校验表名白名单、
 * 强制主键排序；编辑器仍然走原执行链路，两者互不影响。
 */
@RestController
@RequestMapping("/api/db")
@CrossOrigin
public class TableBrowseController {

    private final TableBrowseService service;

    public TableBrowseController(TableBrowseService service) {
        this.service = service;
    }

    /** 表清单（轻量：表名 + 表注释）。 */
    @GetMapping("/tables")
    public List<Map<String, Object>> tables(@RequestParam Long connectionId,
                                            @RequestParam(required = false) String keyword) {
        return service.tables(connectionId, keyword);
    }

    /** 单表结构 + 展示用 DDL。table 用查询参数而非路径段，避免表名带点/斜杠时的路径解析问题。 */
    @GetMapping("/table")
    public Map<String, Object> table(@RequestParam Long connectionId,
                                     @RequestParam String name,
                                     @RequestParam(defaultValue = "false") boolean refresh) {
        return service.table(connectionId, name, refresh);
    }

    /** 按表分页取数。 */
    @PostMapping("/table-data")
    public Map<String, Object> tableData(@RequestBody Map<String, Object> body) {
        return service.rows(body);
    }

    /** 按当前筛选/排序导出 CSV（UTF-8 BOM）。 */
    @PostMapping("/table-export")
    public ResponseEntity<byte[]> export(@RequestBody Map<String, Object> body) {
        String csv = service.exportCsv(body);
        String table = String.valueOf(body.getOrDefault("table", "export"));
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String filename = table + "-" + stamp + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.builder("attachment")
                .filename(filename, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 手动刷新缓存（表清单与结构的缓存默认都不过期，这是唯一的失效入口）。
     *
     * @param table 传表名 = 只清这张表的结构；不传 = 清整个库（清单 + 全部结构 + 联想缓存）
     * @param scope list = 只清表清单（树上 ⟳），忽略 table
     */
    @PostMapping("/cache/refresh")
    public Map<String, Object> refreshCache(@RequestParam Long connectionId,
                                            @RequestParam(required = false) String table,
                                            @RequestParam(defaultValue = "all") String scope) {
        boolean listOnly = "list".equalsIgnoreCase(scope);
        boolean wholeDatabase = table == null || table.trim().isEmpty();
        if (listOnly) {
            service.evictTableList(connectionId);
        } else if (wholeDatabase) {
            service.evictSchema(connectionId);
        } else {
            service.evictTable(connectionId, table);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("scope", listOnly ? "list" : (wholeDatabase ? "database" : "table"));
        out.put("connectionId", connectionId);
        out.put("table", listOnly || wholeDatabase ? null : table);
        return out;
    }
}
