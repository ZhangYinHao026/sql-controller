package com.sxwh.sqlcontroller.service;

import com.sxwh.sqlcontroller.config.OnlineProperties;
import com.sxwh.sqlcontroller.model.OnlineConnection;
import com.sxwh.sqlcontroller.repository.OnlineConnectionRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/** 线上环境配置的增删改查与「一键导入 java 区域」。 */
@Service
public class OnlineConnectionService {
    private final OnlineConnectionRepository repository;
    private final OnlineRegionCatalogService catalog;
    private final OnlineProperties properties;

    public OnlineConnectionService(OnlineConnectionRepository repository, OnlineRegionCatalogService catalog,
                                   OnlineProperties properties) {
        this.repository = repository;
        this.catalog = catalog;
        this.properties = properties;
    }

    public List<OnlineConnection> list(String keyword) {
        return repository.find(keyword);
    }

    /** 供后续「字段对比接入线上」使用：只取启用的区域。 */
    public List<OnlineConnection> listEnabled() {
        return repository.findEnabled();
    }

    public OnlineConnection get(Long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("线上环境不存在：id=" + id));
    }

    public OnlineConnection save(Long id, OnlineConnection input) {
        if (input == null) throw new IllegalArgumentException("参数不能为空");
        if (input.getAreaCode() == null || input.getAreaCode() <= 0) throw new IllegalArgumentException("区域码必须为正整数");
        if (input.getRegionName() == null || input.getRegionName().trim().isEmpty()) throw new IllegalArgumentException("区域名称不能为空");
        String ver = OnlineRegionCatalogService.normalizeVer(input.getVer());
        OnlineConnection target = id == null ? new OnlineConnection() : get(id);
        // 唯一性：同一版本下区域码不可重复
        Optional<OnlineConnection> same = repository.find(null).stream()
                .filter(c -> ver.equals(OnlineRegionCatalogService.normalizeVer(c.getVer()))
                        && input.getAreaCode().equals(c.getAreaCode())
                        && (id == null || !id.equals(c.getId())))
                .findFirst();
        if (same.isPresent()) throw new IllegalArgumentException("该版本下区域码已存在：" + input.getAreaCode() + "（" + same.get().label() + "）");

        target.setAreaCode(input.getAreaCode());
        target.setRegionName(input.getRegionName().trim());
        target.setDbType(normalizeDbType(input.getDbType()));
        target.setDisplayName(input.getDisplayName() == null ? null : input.getDisplayName().trim());
        target.setVer(ver);
        target.setWriteBaseUrl(trim(input.getWriteBaseUrl()));
        target.setEnabled(input.getEnabled() == null || input.getEnabled());
        target.setRemark(trim(input.getRemark()));
        return repository.save(target);
    }

    public void delete(Long id) {
        repository.delete(id);
    }

    /**
     * 一键导入某版本下所有 java 区域（type=j）。已存在的区域码跳过，不覆盖用户已配的写入地址。
     */
    public Map<String, Object> importRegions(String ver) {
        Map<String, Object> catalogResult = catalog.regions(ver);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> regions = (List<Map<String, Object>>) catalogResult.get("regions");
        String key = String.valueOf(catalogResult.get("ver"));
        int imported = 0, skipped = 0;
        List<String> names = new ArrayList<>();
        if (regions != null) {
            for (Map<String, Object> r : regions) {
                Integer area = toInt(r.get("area"));
                if (area == null) continue;
                if (repository.exists(key, area)) {
                    skipped++;
                    continue;
                }
                OnlineConnection c = new OnlineConnection();
                c.setAreaCode(area);
                c.setRegionName(String.valueOf(r.get("name")));
                c.setVer(key);
                c.setEnabled(true);
                repository.save(c);
                imported++;
                names.add(area + "-" + r.get("name"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ver", key);
        out.put("imported", imported);
        out.put("skipped", skipped);
        out.put("importedNames", names);
        out.put("stale", catalogResult.get("stale"));
        out.put("error", catalogResult.get("error"));
        return out;
    }

    /** 设置页右上角的只读信息（不回传任何密钥）。 */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseUrl", properties.baseUrl());
        out.put("queryPath", "/LocalTest/ToSel");
        out.put("writeEnabled", properties.writeEnabled());
        out.put("writePath", properties.getWrite().getPath());
        out.put("encryptMode", properties.getWrite().normalizedEncryptMode());
        out.put("encryptUrl", properties.getWrite().getEncryptUrl());
        out.put("localKeyConfigured", properties.getWrite().hasLocalKey());
        out.put("allowDelete", properties.getWrite().isAllowDelete());
        out.put("defaultPageSize", properties.getDefaultPageSize());
        return out;
    }

    private static Integer toInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return v == null ? null : Integer.valueOf(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String trim(String v) {
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    /** 库类型只允许已注册的方言（mysql/dm/oscar），空则按 mysql。 */
    private static String normalizeDbType(String v) {
        String t = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return "mysql";
        if (!Arrays.asList("mysql", "dm", "oscar").contains(t)) {
            throw new IllegalArgumentException("不支持的库类型：" + v + "（可选 mysql / dm / oscar）");
        }
        return t;
    }
}
