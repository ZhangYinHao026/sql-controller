package com.sxwh.sqlcontroller.controller;

import com.sxwh.sqlcontroller.dialect.DialectRegistry;
import com.sxwh.sqlcontroller.model.ConvertRequest;
import com.sxwh.sqlcontroller.model.ConvertResult;
import com.sxwh.sqlcontroller.service.SqlConvertService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SQL 方言转换接口：MySQL → 达梦(dm) / 神通(oscar)。
 * 前端调用约定见《SQL转换前端对接文档.md》。
 */
@RestController
@RequestMapping("/api/sql")
@CrossOrigin
public class SqlConvertController {

    private final SqlConvertService service;
    private final DialectRegistry registry;

    public SqlConvertController(SqlConvertService service, DialectRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    /** 转换 SQL：仅支持建表 / 加字段 / 简单 DML，MySQL 特有语法返回 warning 不静默修改。 */
    @PostMapping("/convert")
    public ConvertResult convert(@RequestBody ConvertRequest req) {
        ConvertResult bad = new ConvertResult(req == null ? null : req.getTargetDbType());
        if (req == null || req.getSql() == null || req.getSql().trim().isEmpty()) {
            bad.setSuccess(false);
            bad.setError("SQL 不能为空");
            return bad;
        }
        String target = req.getTargetDbType() == null ? "" : req.getTargetDbType().trim().toLowerCase();
        try {
            registry.get(target); // 校验方言已注册（新增方言后此处自动生效）
        } catch (IllegalArgumentException e) {
            bad.setSuccess(false);
            bad.setError(e.getMessage());
            return bad;
        }
        return service.convert(req.getSql(), target);
    }
}
