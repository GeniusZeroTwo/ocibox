package com.oci.ocimanager.config;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * 从反向代理后的请求里取客户端 IP。
 * 服务只监听回环地址，直连地址永远是本机代理。取 X-Forwarded-For 最右侧一跳
 * （由最近的代理追加），不用客户端可以伪造的第一跳，也不读 CF-Connecting-IP。
 */
public final class ClientIpResolver {

    private static final Pattern IP_PATTERN = Pattern.compile("^[0-9a-fA-F:.]+$");

    private ClientIpResolver() {}

    public static String resolve(HttpServletRequest request) {
        if (request == null) return "UNKNOWN";
        String remote = sanitize(request.getRemoteAddr());
        if (remote == null) remote = "UNKNOWN";
        if (!isLoopback(remote)) {
            return remote;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = sanitize(parts[i]);
                if (candidate != null && !isLoopback(candidate)) {
                    return candidate;
                }
            }
        }

        String realIp = sanitize(request.getHeader("X-Real-IP"));
        if (realIp != null && !isLoopback(realIp)) {
            return realIp;
        }
        return remote;
    }

    private static boolean isLoopback(String ip) {
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip);
    }

    private static String sanitize(String raw) {
        if (raw == null) return null;
        String ip = raw.trim();
        if (ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) return null;
        if (ip.length() > 45 || !IP_PATTERN.matcher(ip).matches()) return null;
        return ip;
    }
}
