package com.sxwh.sqlcontroller.controller;

import com.sxwh.sqlcontroller.service.RowWriteService;
import com.sxwh.sqlcontroller.util.WebUtil;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行级写入口（表浏览「双击单元格改数据」的后端）。
 *
 * <p>与 {@code /api/sql/execute} 的分工：这里只执行**由服务端拼装**的单行 UPDATE/INSERT/DELETE，
 * 不接受任意 SQL；表名/列名走结构快照白名单，值走预编译绑定。
 * 写开关关闭时返回 403（见 {@code sql-controller.write.enabled}）。
 */
@RestController
@RequestMapping("/api/db")
@CrossOrigin
public class RowWriteController {

    private final RowWriteService service;

    public RowWriteController(RowWriteService service) {
        this.service = service;
    }

    /** 前端据此决定是否渲染编辑入口（关掉开关后界面自动退回只读）。 */
    @GetMapping("/write-status")
    public Map<String, Object> writeStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("local", service.localEnabled());
        out.put("online", service.onlineEnabled());
        return out;
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
}
