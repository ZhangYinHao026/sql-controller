package com.sxwh.sqlcontroller.dialect;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 方言注册表：Spring 自动收集所有 Dialect Bean，按 name() 建立索引。
 * 新增数据库时只需新增一个 {@code @Component} 实现类，注册表自动生效，无需改动任何现有代码。
 */
@Component
public class DialectRegistry {

    private final Map<String, Dialect> dialects = new HashMap<>();

    public DialectRegistry(List<Dialect> dialectList) {
        if (dialectList != null) {
            for (Dialect d : dialectList) {
                dialects.put(d.name().toLowerCase(), d);
            }
        }
    }

    /** 按方言名取策略；未知方言抛异常。 */
    public Dialect get(String name) {
        Dialect d = dialects.get(name == null ? "" : name.toLowerCase());
        if (d == null) {
            throw new IllegalArgumentException("不支持的数据库方言: " + name + "（当前可用: " + dialects.keySet() + "）");
        }
        return d;
    }
}
