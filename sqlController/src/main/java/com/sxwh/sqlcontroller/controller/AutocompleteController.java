package com.sxwh.sqlcontroller.controller;

import com.sxwh.sqlcontroller.model.SchemaResponse;
import com.sxwh.sqlcontroller.service.SchemaService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SQL 编辑器联想数据源接口。
 * 前端调用约定见《CodeMirror6_SQL联想升级方案.md》。
 */
@RestController
@RequestMapping("/api/autocomplete")
@CrossOrigin
public class AutocompleteController {

    private final SchemaService service;

    public AutocompleteController(SchemaService service) {
        this.service = service;
    }

    /** 返回表/字段清单（编辑器联想用），来源为配置指定的固定连接；不可用时 enabled=false。
     *  @param force true=跳过缓存重新查询（调试/连接变更后用），默认 false。 */
    @GetMapping("/schema")
    public SchemaResponse schema(@RequestParam(required = false, defaultValue = "false") boolean force) {
        return service.loadSchema(force);
    }
}