package com.sxwh.sqlcontroller.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sxwh.sqlcontroller.config.OnlineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 线上区域清单（来自小助手的 {@code /Content/pageConfig.json}）。
 *
 * <p>该文件同时含 BT18 / BT21 / BF 三套版本，元素形如
 * {@code {area:32,name:"江苏省",pinyin:"jiangsu",type:"j",jiajiemi:false,weburl,serverurl}}。
 * 用户只关心 {@code type == "j"} 的区域（走 Java 接口的省）。</p>
 *
 * <p>缓存策略：内存缓存 {@code pageConfigTtlSeconds}（默认 1h）；拉取失败时回退到旧缓存并标记 stale。</p>
 */
@Service
public class OnlineRegionCatalogService {
    private static final Logger log = LoggerFactory.getLogger(OnlineRegionCatalogService.class);

    private final OnlineProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate http;

    private volatile Map<String, List<Map<String, Object>>> cache;
    private volatile long cacheAt;
    private volatile String lastError;

    public OnlineRegionCatalogService(OnlineProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Math.max(1, properties.getConnectTimeoutSeconds()) * 1000);
        f.setReadTimeout(Math.max(1, properties.getReadTimeoutSeconds()) * 1000);
        this.http = new RestTemplate(f);
    }

    /** 区域清单（已过滤 type=j）。ver 为空时用 BT21。 */
    public Map<String, Object> regions(String ver) {
        String key = normalizeVer(ver);
        Map<String, List<Map<String, Object>>> doc = document();
        List<Map<String, Object>> all = doc.get(key);
        if (all == null) {
            // 版本键缺失时退回任意一套，避免前端完全空白
            all = doc.values().stream().findFirst().orElse(Collections.emptyList());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : all) {
            if (!"j".equalsIgnoreCase(String.valueOf(item.get("type")))) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("area", item.get("area"));
            row.put("name", item.get("name"));
            row.put("pinyin", item.get("pinyin"));
            row.put("jiajiemi", Boolean.TRUE.equals(item.get("jiajiemi")));
            row.put("ver", key);
            result.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ver", key);
        out.put("regions", result);
        out.put("stale", cache != null && lastError != null);
        out.put("error", lastError);
        out.put("fetchedAt", cacheAt);
        out.put("baseUrl", properties.baseUrl());
        return out;
    }

    /** 原始版本键（BT18/BT21/BF）。 */
    public static String normalizeVer(String ver) {
        if (ver == null) return "BT21";
        String v = ver.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? "BT21" : v;
    }

    private Map<String, List<Map<String, Object>>> document() {
        long now = System.currentTimeMillis();
        long ttl = Math.max(60, properties.getPageConfigTtlSeconds()) * 1000L;
        Map<String, List<Map<String, Object>>> snapshot = cache;
        if (snapshot != null && now - cacheAt < ttl) return snapshot;
        synchronized (this) {
            if (cache != null && System.currentTimeMillis() - cacheAt < ttl) return cache;
            try {
                String url = properties.baseUrl() + "/Content/pageConfig.json";
                ResponseEntity<byte[]> resp = http.exchange(url, HttpMethod.GET, new HttpEntity<Void>(new HttpHeaders()), byte[].class);
                byte[] body = resp.getBody();
                if (body == null || body.length == 0) throw new IllegalStateException("区域清单为空");
                String text = new String(body, StandardCharsets.UTF_8);
                if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
                Map<String, List<Map<String, Object>>> parsed = mapper.readValue(text,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, List<Map<String, Object>>>>() {
                        });
                cache = parsed;
                cacheAt = System.currentTimeMillis();
                lastError = null;
                return parsed;
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("[online] 拉取区域清单失败：{}", lastError);
                if (cache != null) return cache;
                return Collections.emptyMap();
            }
        }
    }
}
