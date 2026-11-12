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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 线上写入：生成加密串 → 调目标省份的 {@code /api/apply/updateSql}。
 *
 * <p>加密串默认由<b>中心加密接口</b>生成（{@code POST /api/sxwh/updateSqlSm}，2026-10-23 核对）：
 * 请求体是 JSON 字符串字面量（明文 SQL + 权限后缀 {@code sxwhkj@2024}），响应体也是 JSON 字符串字面量（hex 密文）。
 * 拿到加密串后原样作为请求体 POST 给对应省份的 updateSql；服务端 strip 掉权限后缀后才执行。</p>
 *
 * <p>各区域写地址单独配置（每省一套部署）；未配置 writeBaseUrl 的区域只能查不能写。</p>
 */
@Service
public class OnlineWriteService {
    private static final Logger log = LoggerFactory.getLogger(OnlineWriteService.class);

    private final OnlineProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate http;
    /** DDL 档专用：大表 ALTER 可能远超 60s（超时分档，2026-10-24）。 */
    private final RestTemplate ddlHttp;
    /** 专用于中心加密接口（超时更短，避免写通道被加密服务拖死）。 */
    private final RestTemplate cryptoHttp;

    public OnlineWriteService(OnlineProperties properties) {
        this.properties = properties;
        this.http = newHttpClient(properties.getWrite().getTimeoutSeconds());
        this.ddlHttp = newHttpClient(properties.getWrite().getDdlTimeoutSeconds());
        this.cryptoHttp = newHttpClient(properties.getWrite().getEncryptTimeoutSeconds());
    }

    /**
     * 组装写通道的 HTTP 客户端。
     * trust-all-tls 开启时放宽两件事（默认开）：
     * ① 证书主机名校验——广西等省配的是 IP 地址（https://116.252.25.89:8088），
     *    而对方返回的是正式域名证书（*.gxnjzx.cn），IP 与证书不匹配，Java 默认严格校验直接握手失败，
     *    界面上就是「调用写入接口失败（url）：hostname in certificate didn't match」；
     * ② 证书链校验——部分省用自签/新 CA 证书，老 JDK cacerts 里未必有根。
     * 只放宽传输层校验，不影响写入权限后缀等业务防护。
     */
    private RestTemplate newHttpClient(int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                if (properties.getWrite().isTrustAllTls() && connection instanceof HttpsURLConnection) {
                    HttpsURLConnection https = (HttpsURLConnection) connection;
                    https.setHostnameVerifier(trustAllHostnameVerifier());
                    https.setSSLSocketFactory(trustAllSocketFactory());
                }
                super.prepareConnection(connection, httpMethod);
            }
        };
        f.setConnectTimeout(Math.max(1, properties.getConnectTimeoutSeconds()) * 1000);
        f.setReadTimeout(Math.max(1, readTimeoutSeconds) * 1000);
        return new RestTemplate(f);
    }

    private HostnameVerifier trustAllHostnameVerifier() {
        return (hostname, session) -> true;
    }

    private volatile SSLSocketFactory trustAllFactoryCache;

    private SSLSocketFactory trustAllSocketFactory() {
        SSLSocketFactory cached = trustAllFactoryCache;
        if (cached != null) return cached;
        synchronized (this) {
            if (trustAllFactoryCache == null) {
                TrustManager[] tms = new TrustManager[]{new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }};
                try {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, tms, new SecureRandom());
                    trustAllFactoryCache = ctx.getSocketFactory();
                } catch (Exception e) {
                    throw new IllegalStateException("初始化宽松 SSL 上下文失败：" + e.getMessage(), e);
                }
            }
            return trustAllFactoryCache;
        }
    }

    /** 执行一条写语句；调用方必须已完成二次确认。 */
    public Map<String, Object> write(OnlineConnection c, String rawSql) {
        long started = System.currentTimeMillis();
        Map<String, Object> item = new LinkedHashMap<>();
        String mode = properties.getWrite().normalizedEncryptMode();
        item.put("connectionId", c.getId());
        item.put("scope", "online");
        item.put("dbType", "online");
        item.put("areaCode", c.getAreaCode());
        item.put("regionName", c.label());
        item.put("dbDialect", c.getDbType());
        item.put("encryptMode", mode);
        item.put("source", "local".equals(mode) ? "写入接口（updateSql · 本地加密）" : "写入接口（updateSql · 中心加密）");
        try {
            if (!properties.writeEnabled()) throw new IllegalStateException("服务端未配置加密方式（中心加密接口地址或 SM4 密钥缺失），写入通道未启用");
            String base = trimUrl(c.getWriteBaseUrl());
            if (base.isEmpty()) throw new IllegalStateException("该区域未配置写入地址（仅支持查询）");
            String sql = OnlineSqlGuard.prepareWrite(rawSql, properties.getWrite().isAllowDelete());
            String plain = plainFor(sql, properties.getWrite().getPermissionSuffix());
            String hex = encrypt(plain);

            String url = base + properties.getWrite().getPath();
            // 超时分档：DDL（大表 ALTER 可达分钟级）用长档，DML/未分类走 60s 短档
            ResponseEntity<byte[]> resp = postJsonString(isDdlStatement(sql) ? ddlHttp : http, url, hex, "写入接口");
            String text = bodyText(resp);

            String msg;
            int code = 500;
            try {
                Map<?, ?> body = mapper.readValue(text, LinkedHashMap.class);
                code = toInt(body.get("code"), resp.getStatusCodeValue() == 200 ? 200 : 500);
                msg = str(body.get("msg"), str(body.get("message"), truncate(text)));
            } catch (Exception e) {
                throw new IllegalStateException("写入接口返回非 JSON（HTTP " + resp.getStatusCodeValue() + "）：" + truncate(text)
                        + "；若为 415，请检查 sql-controller.online.write.path（应指向该省 /api/sxwh/updateSql）");
            }
            if (code != 200) {
                item.put("status", "FAILED");
                item.put("errorMessage", msg == null || msg.isEmpty() ? "写入失败（code=" + code + "）" : msg);
                if (code == 401) item.put("errorMessage", msg + "（该路径需要登录态，请改用 /api/sxwh/updateSql）");
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

    /** 明文 = SQL + 换行 + 权限后缀（服务端**按后缀判定权限**，命中后 strip 再执行）。 */
    static String plainFor(String sql, String suffix) {
        String s = sql == null ? "" : sql.trim();
        String suf = suffix == null ? "" : suffix.trim();
        return suf.isEmpty() ? s : s + "\n" + suf;
    }

    private static final java.util.Set<String> DDL_VERBS = new java.util.HashSet<>(java.util.Arrays.asList(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME"));

    /**
     * 首词分档：CREATE/ALTER/DROP/TRUNCATE/RENAME → DDL（与本地 {@code DynamicDataSourceService.timeoutFor} 同口径）。
     * 括号包裹等怪形态保守按 DML 处理。
     */
    static boolean isDdlStatement(String sql) {
        String s = sql == null ? "" : sql.trim();
        if (s.isEmpty() || s.charAt(0) == '(') return false;
        String first = s.split("\\s+", 2)[0].toUpperCase(java.util.Locale.ROOT);
        return DDL_VERBS.contains(first);
    }

    /** 生成加密串：remote=中心加密接口（默认），local=本地 SM4，auto=远程失败回落本地。 */
    String encrypt(String plain) {
        String mode = properties.getWrite().normalizedEncryptMode();
        if ("local".equals(mode)) {
            if (!properties.getWrite().hasLocalKey()) throw new IllegalStateException("加密方式为 local，但未配置 sm4-key");
            return Sm4Util.encryptHex(properties.getWrite().getSm4Key(), plain);
        }
        try {
            return encryptRemote(plain);
        } catch (RuntimeException e) {
            if ("auto".equals(mode) && properties.getWrite().hasLocalKey()) {
                log.warn("[online-write] 中心加密接口不可用，回落本地 SM4：{}", e.getMessage());
                return Sm4Util.encryptHex(properties.getWrite().getSm4Key(), plain);
            }
            throw e;
        }
    }

    /**
     * 调中心加密接口：{@code POST /api/sxwh/updateSqlSm}。
     *
     * <p>请求体必须是 <b>JSON 字符串字面量</b>（例如 {@code "UPDATE t SET a=1\nsxwhkj@2024"}，
     * Content-Type: application/json，text/plain 会 415）；响应体同样是 JSON 字符串字面量（hex 密文）。</p>
     */
    private String encryptRemote(String plain) {
        String url = properties.getWrite().getEncryptUrl() == null ? "" : properties.getWrite().getEncryptUrl().trim();
        if (url.isEmpty()) throw new IllegalStateException("未配置中心加密接口地址（sql-controller.online.write.encrypt-url）");
        ResponseEntity<byte[]> resp = postJsonString(cryptoHttp, url, plain, "加密接口");
        return parseEncryptResponse(bodyText(resp), mapper);
    }

    /** 解析加密接口响应：支持 JSON 字符串字面量（正常）与错误 JSON 对象（{code,msg}）。 */
    static String parseEncryptResponse(String text, ObjectMapper mapper) {
        String t = text == null ? "" : text.trim();
        if (!t.isEmpty() && t.charAt(0) == '\uFEFF') t = t.substring(1).trim();
        if (t.isEmpty()) throw new IllegalStateException("加密接口返回空响应");
        if (t.startsWith("{")) {
            String msg = t;
            try {
                Map<?, ?> body = mapper.readValue(t, LinkedHashMap.class);
                Object m = body.get("msg");
                if (m == null) m = body.get("message");
                if (m != null) msg = String.valueOf(m);
            } catch (Exception ignore) {
                // 解析不了就原样截断返回
            }
            throw new IllegalStateException("加密接口返回错误：" + truncate(msg));
        }
        String hex = t;
        if (hex.startsWith("\"")) {
            try {
                hex = mapper.readValue(hex, String.class);
            } catch (Exception e) {
                throw new IllegalStateException("加密接口返回无法解析的字符串：" + truncate(t));
            }
        }
        hex = hex == null ? "" : hex.trim();
        if (!hex.matches("[0-9a-fA-F]{32,}") || (hex.length() & 1) == 1) {
            throw new IllegalStateException("加密接口返回的不是合法加密串：" + truncate(t));
        }
        return hex.toLowerCase(java.util.Locale.ROOT);
    }

    /** 以 JSON 字符串字面量 POST（两个后端接口用 @RequestBody String 接收，text/plain 会 415）。 */
    private ResponseEntity<byte[]> postJsonString(RestTemplate client, String url, String raw, String what) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body;
        try {
            body = mapper.writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalStateException(what + "请求体序列化失败：" + e.getMessage(), e);
        }
        try {
            return client.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
        } catch (RestClientResponseException e) {
            String detail = e.getRawStatusCode() == 415 ? "（Content-Type 不被接受）" : "";
            throw new IllegalStateException(what + "返回 HTTP " + e.getRawStatusCode() + detail + "：" + truncate(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            throw new IllegalStateException("调用" + what + "失败（" + url + "）：" + e.getMessage(), e);
        }
    }

    private static String bodyText(ResponseEntity<byte[]> resp) {
        String text = resp.getBody() == null ? "" : new String(resp.getBody(), StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        return text;
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
