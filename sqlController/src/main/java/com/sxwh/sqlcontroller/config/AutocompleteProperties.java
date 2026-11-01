package com.sxwh.sqlcontroller.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SQL 编辑器联想（autocomplete）配置。
 *
 * <p>对应 application.yml 的 {@code sql-controller.autocomplete.*}，开源部署可全部通过配置文件调整。
 */
@Component
@ConfigurationProperties(prefix = "sql-controller.autocomplete")
public class AutocompleteProperties {

    /** 总开关：false 时接口返回 enabled=false，前端降级为纯关键字联想。 */
    private boolean enabled = true;

    /** 联想数据源连接：auto = 自动取 database_connection 第一条记录；或填写固定 connectionId。 */
    private String schemaSource = "auto";

    /** 后端 schema 缓存 TTL（秒）。结构元数据很少变，默认 24h；「强制重抓」可随时绕过。 */
    private long schemaCacheSeconds = 86400;

    /** 表数量上限，防超大库拖垮联想请求。 */
    private int maxTables = 500;

    /** 单表字段数量上限。 */
    private int maxColumns = 100;

    /**
     * 表浏览「单表结构」缓存 TTL（秒）。
     *
     * <p>负数 = 永不过期（默认：查一次管到服务重启，靠右键「刷新表结构 / 重拉整库结构」手动刷新）；
     * 0 = 关闭缓存（每次真查）；正数 = 到期自动重查。
     *
     * <p>为什么默认不过期：表浏览的读操作（翻页/排序/筛选/导出）都发生在几十秒内，
     * 而结构变更本身极低频；TTL 到点造成的「莫名变慢」比这点陈旧更难解释。
     * 写操作（改数据/改结构）不走这层缓存，永远真查，所以不存在「外部加字段后永久写不进去」的风险。
     */
    private long tableCacheSeconds = -1;

    /** 单表结构缓存条数上限（全局）。超过后按写入顺序淘汰最旧的，防止长期运行内存无界增长。 */
    private int tableCacheMaxEntries = 2000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSchemaSource() {
        return schemaSource;
    }

    public void setSchemaSource(String schemaSource) {
        this.schemaSource = schemaSource;
    }

    public long getSchemaCacheSeconds() {
        return schemaCacheSeconds;
    }

    public void setSchemaCacheSeconds(long schemaCacheSeconds) {
        this.schemaCacheSeconds = schemaCacheSeconds;
    }

    public int getMaxTables() {
        return maxTables;
    }

    public void setMaxTables(int maxTables) {
        this.maxTables = maxTables;
    }

    public int getMaxColumns() {
        return maxColumns;
    }

    public void setMaxColumns(int maxColumns) {
        this.maxColumns = maxColumns;
    }

    public long getTableCacheSeconds() {
        return tableCacheSeconds;
    }

    public void setTableCacheSeconds(long tableCacheSeconds) {
        this.tableCacheSeconds = tableCacheSeconds;
    }

    public int getTableCacheMaxEntries() {
        return tableCacheMaxEntries;
    }

    public void setTableCacheMaxEntries(int tableCacheMaxEntries) {
        this.tableCacheMaxEntries = tableCacheMaxEntries;
    }
}
