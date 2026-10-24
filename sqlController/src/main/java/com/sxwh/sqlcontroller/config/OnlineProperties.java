package com.sxwh.sqlcontroller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 线上环境（远程查询小助手 / 各省写入接口）相关配置，从 application.yml 的 {@code sql-controller.online} 节点读取。
 *
 * <p>敏感项（SM4 密钥、权限后缀）只在后端保存，接口一律不回传给前端。</p>
 */
@Component
@ConfigurationProperties(prefix = "sql-controller.online")
public class OnlineProperties {
    /** 远程查询小助手根地址（所有区域共用，区域差异只体现在 area 参数）。 */
    private String baseUrl = "http://192.168.0.2:808";
    /** pageConfig.json（区域清单）缓存秒数。 */
    private int pageConfigTtlSeconds = 3600;
    /** 访问小助手的连接超时秒数。 */
    private int connectTimeoutSeconds = 10;
    /** 访问小助手的读取超时秒数（跨省网络较慢，留足余量）。 */
    private int readTimeoutSeconds = 120;
    /** 多区域并行查询的最大并发数。 */
    private int maxConcurrency = 8;
    /** 未指定时的默认每页条数（与小助手页面默认一致）。 */
    private int defaultPageSize = 10;
    /** 每页条数上限（小助手自身未做限制，这里做保护）。 */
    private int maxPageSize = 500;
    /** 写入通道配置。 */
    private Write write = new Write();

    public static class Write {
        /**
         * 加密串生成方式：
         * <ul>
         *   <li>{@code remote}（默认）：POST 明文到中心加密接口 {@link #encryptUrl}，用返回的加密串写各省；</li>
         *   <li>{@code local}：用本地 {@link #sm4Key} 自己算（离线/排障用）；</li>
         *   <li>{@code auto}：先远程，失败再回落本地（需配 sm4Key）。</li>
         * </ul>
         */
        private String encryptMode = "remote";
        /** 中心加密接口（入参明文 JSON 字符串，出参 hex 加密串）。 */
        private String encryptUrl = "http://111.56.142.13:18080/api/sxwh/updateSqlSm";
        /** 调中心加密接口的超时秒数。 */
        private int encryptTimeoutSeconds = 30;
        /** SM4 ECB 密钥（hex，32 字符）：仅 local / auto 回落时使用，remote 模式可为空。 */
        private String sm4Key = "";
        /** 权限后缀，明文 SQL 末尾必须带，服务端 strip 后才执行。 */
        private String permissionSuffix = "sxwhkj@2024";
        /** 写入接口超时秒数（DML/未分类档）。 */
        private int timeoutSeconds = 60;
        /**
         * DDL 档写入超时秒数（2026-10-24 用户拍板按语句分档）。
         * 大表 ALTER 在线上可能远超 60s——数据库还在执行、前端先断会误报失败。
         * 首词 ∈ CREATE/ALTER/DROP/TRUNCATE/RENAME 走本档（与本地 DynamicDataSourceService.timeoutFor 同口径）。
         */
        private int ddlTimeoutSeconds = 600;
        /** 是否允许 DELETE（内部开发工具，默认开；如线上要收紧可置 false）。 */
        private boolean allowDelete = true;
        /** 写入接口路径。默认 {@code /api/sxwh/updateSql}（实测可用；{@code /api/apply/updateSql} 需登录态）。 */
        private String path = "/api/sxwh/updateSql";

        public String getEncryptMode() { return encryptMode; }
        public void setEncryptMode(String encryptMode) { this.encryptMode = encryptMode; }
        public String getEncryptUrl() { return encryptUrl; }
        public void setEncryptUrl(String encryptUrl) { this.encryptUrl = encryptUrl; }
        public int getEncryptTimeoutSeconds() { return encryptTimeoutSeconds; }
        public void setEncryptTimeoutSeconds(int encryptTimeoutSeconds) { this.encryptTimeoutSeconds = encryptTimeoutSeconds; }
        public String getSm4Key() { return sm4Key; }
        public void setSm4Key(String sm4Key) { this.sm4Key = sm4Key; }

        /** 规范化后的加密方式（未知值一律按 remote 处理）。 */
        public String normalizedEncryptMode() {
            String m = encryptMode == null ? "" : encryptMode.trim().toLowerCase(java.util.Locale.ROOT);
            if ("local".equals(m) || "auto".equals(m)) return m;
            return "remote";
        }
        /** 是否配置了中心加密接口。 */
        public boolean hasEncryptUrl() { return encryptUrl != null && !encryptUrl.trim().isEmpty(); }
        /** 是否配置了本地 SM4 密钥（local 模式必须，auto 模式用于回落）。 */
        public boolean hasLocalKey() { return sm4Key != null && !sm4Key.trim().isEmpty(); }
        public String getPermissionSuffix() { return permissionSuffix; }
        public void setPermissionSuffix(String permissionSuffix) { this.permissionSuffix = permissionSuffix; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getDdlTimeoutSeconds() { return ddlTimeoutSeconds; }
        public void setDdlTimeoutSeconds(int ddlTimeoutSeconds) { this.ddlTimeoutSeconds = ddlTimeoutSeconds; }
        public boolean isAllowDelete() { return allowDelete; }
        public void setAllowDelete(boolean allowDelete) { this.allowDelete = allowDelete; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public int getPageConfigTtlSeconds() { return pageConfigTtlSeconds; }
    public void setPageConfigTtlSeconds(int pageConfigTtlSeconds) { this.pageConfigTtlSeconds = pageConfigTtlSeconds; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(int readTimeoutSeconds) { this.readTimeoutSeconds = readTimeoutSeconds; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public int getDefaultPageSize() { return defaultPageSize; }
    public void setDefaultPageSize(int defaultPageSize) { this.defaultPageSize = defaultPageSize; }
    public int getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(int maxPageSize) { this.maxPageSize = maxPageSize; }
    public Write getWrite() { return write; }
    public void setWrite(Write write) { this.write = write; }

    /** 去掉末尾斜杠的根地址，便于拼路径。 */
    public String baseUrl() {
        String v = baseUrl == null ? "" : baseUrl.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    /** 是否已配置写入通道（remote 看加密接口地址，local 看密钥）。 */
    public boolean writeEnabled() {
        if (write == null) return false;
        String mode = write.normalizedEncryptMode();
        if ("local".equals(mode)) return write.hasLocalKey();
        if ("auto".equals(mode)) return write.hasEncryptUrl() || write.hasLocalKey();
        return write.hasEncryptUrl();
    }

    /** 页大小边界保护。 */
    public int normalizePageSize(Integer value) {
        int size = value == null ? Math.max(1, defaultPageSize) : value;
        return Math.min(Math.max(1, size), Math.max(1, maxPageSize));
    }
}
