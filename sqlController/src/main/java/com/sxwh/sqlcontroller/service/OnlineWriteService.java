package com.sxwh.sqlcontroller.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sxwh.sqlcontroller.config.OnlineProperties;
import com.sxwh.sqlcontroller.model.OnlineConnection;
import com.sxwh.sqlcontroller.util.OnlineSqlGuard;
import com.sxwh.sqlcontroller.util.Sm4Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 线上写入：调用各省 sxwh-allowance 部署的 {@code /api/apply/updateSql}。
 *
 * <p>协议（2026-10-23 核对源码）：请求体是 <b>裸字符串</b>（SM4/ECB/PKCS5 密文的 hex）；
 * 明文 SQL 末尾必须带权限后缀 {@code sxwhkj@2024}，服务端 strip 后才执行。
 * 响应 {@code {code:200, msg:"更新成功N条数据"}} / {@code {code:500, msg:"更新失败原因：…"}}。</p>
 *
 * <p>写地址按区域单独配置（每省各一套部署）；未配置 writeBaseUrl 的区域只能查不能写。</p>
 */
@Service
public class OnlineWriteService {
    private static final Logger log = LoggerFactory.getLogger(OnlineWriteService.class);

    private final OnlineProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate http;

    public OnlineWriteService(OnlineProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Math.max(1, properties.getConnectTimeoutSeconds()) * 1000);
        f.setReadTimeout(Math.max(1, properties.getWrite().getTimeoutSeconds()) * 1000);
        this.http = new RestTemplate(f);
    }

    /** 执行一条写语句；调用方必须已完成二次确认。 */
    public Map<String, Object> write(OnlineConnection c, String rawSql) {
        long started = System.currentTimeMillis();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("connectionId", c.getId());
        item.put("scope", "online");
        item.put("dbType", "online");
        item.put("areaCode", c.getAreaCode());
        item.put("regionName", c.label());
        item.put("dbDialect", c.getDbType());
        item.put("source", "写入接口（updateSql）");
        try {
            if (!properties.writeEnabled()) throw new IllegalStateException("服务端未配置 SM4 密钥，写入通道未启用");
            String base = trimUrl(c.getWriteBaseUrl());
            if (base.isEmpty()) throw new IllegalStateException("该区域未配置写入地址（仅支持查询）");
            String sql = OnlineSqlGuard.prepareWrite(rawSql, properties.getWrite().isAllowDelete());
            String plain = sql + "\n" + properties.getWrite().getPermissionSuffix();
            String hex = Sm4Util.encryptHex(properties.getWrite().getSm4Key(), plain);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/plain; charset=UTF-8"));
            HttpEntity<String> entity = new HttpEntity<>(hex, headers);
            String url = base + properties.getWrite().getPath();
            ResponseEntity<byte[]> resp = http.exchange(url, HttpMethod.POST, entity, byte[].class);
            String text = resp.getBody() == null ? "" : new String(resp.getBody(), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);

            String msg;
            int code;
            try {
                Map<?, ?> body = mapper.readValue(text, LinkedHashMap.class);
                code = toInt(body.get("code"), resp.getStatusCodeValue() == 200 ? 200 : 500);
                msg = str(body.get("msg"), str(body.get("message"), truncate(text)));
            } catch (Exception e) {
                throw new IllegalStateException("写入接口返回非 JSON（HTTP " + resp.getStatusCodeValue() + "）：" + truncate(text));
            }
            if (code != 200) {
                item.put("status", "FAILED");
                item.put("errorMessage", msg == null || msg.isEmpty() ? "写入失败（code=" + code + "）" : msg);
            } else {
                item.put("status", "SUCCESS");
                item.put("message", msg);
                item.put("affectedRows", parseAffected(msg));
                item.put("columns", java.util.Collections.emptyList());
                item.put("rows", java.util.Collections.emptyList());
                item.put("total", 0L);
            }
            log.info("[online-write] area={} {} => {}", c.getAreaCode(), c.getRegionName(), msg);
        } catch (Exception e) {
            item.put("status", "FAILED");
            item.put("errorMessage", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        item.put("durationMs", System.currentTimeMillis() - started);
        return item;
    }

    /** 从「更新成功 3 条数据」里抠出影响行数，抠不到给 0。 */
    private static long parseAffected(String msg) {
        if (msg == null) return 0L;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(msg);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private static String trimUrl(String url) {
        String v = url == null ? "" : url.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static int toInt(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v == null) return def;
        try {
            return (int) Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String str(Object v, String def) {
        return v == null ? def : String.valueOf(v);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }
}
