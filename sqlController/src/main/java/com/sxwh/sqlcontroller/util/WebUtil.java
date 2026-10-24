package com.sxwh.sqlcontroller.util;

import javax.servlet.http.HttpServletRequest;

/**
 * Web 请求小工具。
 *
 * <p>取操作人 IP：前端一律经 nginx（sql-controller-ui）反代到 8083，
 * nginx 已配 {@code proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for}（以及 REMOTE-HOST），
 * 故优先读 X-Forwarded-For 首段（多级代理时第一个才是真实客户端），回退 X-Real-IP / remoteAddr。</p>
 */
public final class WebUtil {

    private WebUtil() {
    }

    public static String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty() && !"unknown".equalsIgnoreCase(first)) return first;
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.trim().isEmpty()) return real.trim();
        return request.getRemoteAddr();
    }
}
