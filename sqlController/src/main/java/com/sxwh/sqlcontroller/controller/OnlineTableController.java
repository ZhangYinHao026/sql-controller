package com.sxwh.sqlcontroller.controller;

import com.sxwh.sqlcontroller.service.OnlineTableBrowseService;
import com.sxwh.sqlcontroller.util.WebUtil;
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

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 线上（生产）表浏览接口，与本地 {@code /api/db} 同构——前端只按 env 换前缀。
 *
 * <p>读走小助手只读通道（内联转义值），写走 SM4 updateSql 单语句通道，
 * 并受 {@code sql-controller.write.online-enabled} 控制。
 */
@RestController
@RequestMapping("/api/online/db")
@CrossOrigin
public class OnlineTableController {

    private final OnlineTableBrowseService service;

    public OnlineTableController(OnlineTableBrowseService service) {
        this.service = service;
    }

    @GetMapping("/tables")
    public List<Map<String, Object>> tables(@RequestParam Long connectionId,
                                            @RequestParam(required = false) String keyword) {
        return service.tables(connectionId, keyword);
    }

    @GetMapping("/table")
    public Map<String, Object> table(@RequestParam Long connectionId, @RequestParam String name,
                                     @RequestParam(defaultValue = "false") boolean refresh) {
        return service.table(connectionId, name, refresh);
    }

    /**
     * 手动刷新线上结构缓存（与本地 /api/db/cache/refresh 同构）。
     *
     * @param table 传表名 = 只清这张表；不传 = 重拉整个区域
     */
    @PostMapping("/cache/refresh")
    public Map<String, Object> refreshCache(@RequestParam Long connectionId,
                                            @RequestParam(required = false) String table) {
        boolean wholeDatabase = table == null || table.trim().isEmpty();
        if (wholeDatabase) {
            service.evictSchema(connectionId);
        } else {
            service.evictTable(connectionId, table);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true);
        out.put("scope", wholeDatabase ? "database" : "table");
        out.put("connectionId", connectionId);
        out.put("table", wholeDatabase ? null : table);
        return out;
    }

    @PostMapping("/table-data")
    public Map<String, Object> tableData(@RequestBody Map<String, Object> body) {
        return service.rows(body);
    }

    @PostMapping("/table-export")
    public ResponseEntity<byte[]> export(@RequestBody Map<String, Object> body) {
        String csv = service.exportCsv(body);
        String table = String.valueOf(body.getOrDefault("table", "export"));
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.builder("attachment")
                .filename(table + "-online-" + stamp + ".csv", StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/rows/update")
    public Map<String, Object> update(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.update(body, WebUtil.clientIp(request));
    }

    @PostMapping("/rows/insert")
    public Map<String, Object> insert(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.insert(body, WebUtil.clientIp(request));
    }

    @PostMapping("/rows/delete")
    public Map<String, Object> delete(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.delete(body, WebUtil.clientIp(request));
    }

    @PostMapping("/structure/preview")
    public Map<String, Object> previewStructure(@RequestBody Map<String, Object> body) {
        return service.previewStructure(body);
    }

    @PostMapping("/ddl/execute")
    public Map<String, Object> executeDdl(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.executeDdl(body, WebUtil.clientIp(request));
    }
}
