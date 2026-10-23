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
        /** SM4 ECB 密钥（hex，32 字符）。为空表示未启用写入通道。 */
        private String sm4Key = "";
        /** 权限后缀，明文 SQL 末尾必须带，服务端 strip 后才执行。 */
        private String permissionSuffix = "sxwhkj@2024";
        /** 写入接口超时秒数。 */
        private int timeoutSeconds = 60;
        /** 是否允许 DELETE（内部开发工具，默认开；如线上要收紧可置 false）。 */
        private boolean allowDelete = true;
        /** 写入接口路径，默认 sxwh-allowance 的 /api/apply/updateSql。 */
        private String path = "/api/apply/updateSql";

        public String getSm4Key() { return sm4Key; }
        public void setSm4Key(String sm4Key) { this.sm4Key = sm4Key; }
        public String getPermissionSuffix() { return permissionSuffix; }
        public void setPermissionSuffix(String permissionSuffix) { this.permissionSuffix = permissionSuffix; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
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

    /** 是否已配置写入通道（有密钥即视为启用）。 */
    public boolean writeEnabled() {
        return write != null && write.getSm4Key() != null && !write.getSm4Key().trim().isEmpty();
    }

    /** 页大小边界保护。 */
    public int normalizePageSize(Integer value) {
        int size = value == null ? Math.max(1, defaultPageSize) : value;
        return Math.min(Math.max(1, size), Math.max(1, maxPageSize));
    }
}
