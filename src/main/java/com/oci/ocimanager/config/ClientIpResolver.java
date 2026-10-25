package com.oci.ocimanager.config;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * 取客户端 IP。默认只认 TCP 对端地址。
 * 面板默认只监听回环，隧道和反向代理的对端也是 127.0.0.1。
 * 这时如果再信客户端带来的 X-Forwarded-For，登录锁定可以被换来源绕开。
 * 只有显式打开 app.trust-forwarded-for，并且对端确实是回环地址时，才取转发头最右侧一跳。
 */
public final class ClientIpResolver {

    private static final Pattern IP_PATTERN = Pattern.compile("^[0-9a-fA-F:.]+$");

    private ClientIpResolver() {}

    public static String resolve(HttpServletRequest request) {
        return resolve(request, false);
    }

    public static String resolve(HttpServletRequest request, boolean trustForwarded) {
        if (request == null) return "UNKNOWN";
        String remote = sanitize(request.getRemoteAddr());
        if (remote == null) remote = "UNKNOWN";
        if (!trustForwarded || !isLoopback(remote)) {
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
