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
}
