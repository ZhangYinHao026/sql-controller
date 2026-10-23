package com.sxwh.sqlcontroller.model;

/**
 * 线上环境（每区域一条）。
 *
 * <p>与本地 {@link DatabaseConnection} 完全独立：线上没有 JDBC 地址/账号，只有「区域码 + 版本」，
 * 查询统一经远程查询小助手代理，写入走该省部署的 updateSql 接口。</p>
 *
 * <p>ID 空间与本地连接独立，前端/接口里以 {@code online:<id>} 形式区分（见 README「环境」约定）。</p>
 */
public class OnlineConnection {
    private Long id;
    /** 小助手区域码，如 32=江苏省。 */
    private Integer areaCode;
    /** 区域名，如「江苏省」。 */
    private String regionName;
    /**
     * 该区域库的类型：mysql / dm / oscar。
     *
     * <p>查询与写入不关心它（统一走小助手/updateSql），但后续「字段对比把本地结构同步到线上」时，
     * 补丁生成与 SQL 转换必须知道目标方言，因此现在就随区域一起记录。</p>
     */
    private String dbType = "mysql";
    /** 展示名，空则回落到 regionName。 */
    private String displayName;
    /** 版本：BT18 / BT21 / BF。 */
    private String ver;
    /** 该省 allowance 部署地址；空表示仅可查不可写。 */
    private String writeBaseUrl;
    private Boolean enabled;
    private String remark;

    /** 展示用名称（displayName 优先）。 */
    public String label() {
        return displayName == null || displayName.trim().isEmpty() ? regionName : displayName.trim();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getAreaCode() { return areaCode; }
    public void setAreaCode(Integer areaCode) { this.areaCode = areaCode; }
    public String getRegionName() { return regionName; }
    public void setRegionName(String regionName) { this.regionName = regionName; }
    public String getDbType() { return dbType; }
    public void setDbType(String dbType) { this.dbType = dbType; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getVer() { return ver; }
    public void setVer(String ver) { this.ver = ver; }
    public String getWriteBaseUrl() { return writeBaseUrl; }
    public void setWriteBaseUrl(String writeBaseUrl) { this.writeBaseUrl = writeBaseUrl; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
