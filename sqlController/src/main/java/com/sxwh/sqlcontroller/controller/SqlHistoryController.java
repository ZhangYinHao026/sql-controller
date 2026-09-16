package com.sxwh.sqlcontroller.controller;

import com.sxwh.sqlcontroller.repository.HistoryRepository;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/sql/history")
@CrossOrigin
public class SqlHistoryController {
    private final HistoryRepository repo;

    public SqlHistoryController(HistoryRepository r) {
        repo = r;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String dbType, @RequestParam(required = false) String regionName, @RequestParam(required = false) String status) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, pageSize));
        Map<String, Object> result = new HashMap<>();
        result.put("content", repo.list(p, s, dbType, regionName, status));
        result.put("total", repo.count(dbType, regionName, status));
        result.put("page", p);
        result.put("pageSize", s);
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return repo.get(id);
    }
}
