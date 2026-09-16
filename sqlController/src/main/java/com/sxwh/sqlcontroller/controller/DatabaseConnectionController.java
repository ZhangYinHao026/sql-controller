package com.sxwh.sqlcontroller.controller;

import com.sxwh.sqlcontroller.model.*;
import com.sxwh.sqlcontroller.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 数据库连接配置管理接口。 */
@RestController
@RequestMapping("/api/database")
@CrossOrigin
public class DatabaseConnectionController {
    private final DatabaseConnectionService service;
    private final DynamicDataSourceService dataSourceService;

    public DatabaseConnectionController(DatabaseConnectionService s, DynamicDataSourceService d) {
        service = s;
        dataSourceService = d;
    }

    /** 查询数据库类型及连接数量。 */
    @GetMapping("/types")
    public Object types() {
        return service.types();
    }

    /** 查询连接配置列表，内部开发平台返回完整连接信息。 */
    @GetMapping("/connections")
    public List<DatabaseConnection> list(@RequestParam(required = false) String dbType, @RequestParam(required = false) String keyword) {
        return service.list(dbType, keyword);
    }

    /** 新增数据库连接配置。 */
    @PostMapping("/connections")
    public DatabaseConnection add(@RequestBody ConnectionRequest r) {
        return service.save(null, r);
    }

    /** 编辑数据库连接配置。 */
    @PutMapping("/connections/{id}")
    public DatabaseConnection edit(@PathVariable Long id, @RequestBody ConnectionRequest r) {
        return service.save(id, r);
    }

    /** 删除数据库连接配置。 */
    @DeleteMapping("/connections/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** 测试指定数据库连接。 */
    @PostMapping("/connections/{id}/test")
    public Map<String, Object> test(@PathVariable Long id) {
        try {
            return dataSourceService.testConnection(service.get(id));
        } catch (Exception e) {
            return new HashMap<String, Object>() {{
                put("success", false);
                put("errorMessage", e.getClass().getSimpleName() + ": " + e.getMessage());
            }};
        }
    }

    /** 测试尚未保存的连接配置。 */
    @PostMapping("/connections/test-draft")
    public Map<String, Object> testDraft(@RequestBody ConnectionRequest request) {
        try {
            DatabaseConnection c = new DatabaseConnection();
            c.setDbType(request.getDbType());
            c.setRegionName(request.getRegionName());
            c.setHost(request.getHost());
            c.setPort(request.getPort());
            c.setDatabaseName(request.getDatabaseName());
            c.setJdbcUrl(request.getJdbcUrl());
            c.setUsername(request.getUsername());
            c.setPassword(request.getPassword());
            c.setDriverClass(service.platform(request.getDbType()).driverClass());
            return dataSourceService.testConnection(c);
        } catch (Exception e) {
            return new HashMap<String, Object>() {{
                put("success", false);
                put("errorMessage", e.getClass().getSimpleName() + ": " + e.getMessage());
            }};
        }
    }
}
