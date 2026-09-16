package com.sxwh.sqlcontroller.platform;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Spring 自动收集 JDBC 平台策略，新增数据库无需修改调用代码。 */
@Component
public class JdbcPlatformRegistry {
    private final Map<String, JdbcPlatform> platforms;

    public JdbcPlatformRegistry(List<JdbcPlatform> platformList) {
        Map<String, JdbcPlatform> values = new LinkedHashMap<>();
        for (JdbcPlatform platform : platformList) {
            String key = normalize(platform.name());
            if (values.put(key, platform) != null) throw new IllegalStateException("存在重复 JDBC 平台: " + key);
        }
        platforms = Collections.unmodifiableMap(values);
    }

    /** 根据数据库类型获得执行平台，不支持时给出当前已注册类型。 */
    public JdbcPlatform get(String dbType) {
        JdbcPlatform platform = platforms.get(normalize(dbType));
        if (platform == null) throw new IllegalArgumentException("不支持的数据库类型: " + dbType + "（当前可用: " + platforms.keySet() + "）");
        return platform;
    }

    private String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}
