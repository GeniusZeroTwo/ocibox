package com.oci.ocimanager.config;

import com.oci.ocimanager.service.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 🛡️ 全局后台集中鉴权拦截器 (Defense-in-Depth)
 * 拦截所有 /api/admin/** 敏感运维接口，提供请求头级快速熔断拦截。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    @Autowired
    private AdminAuthService adminAuthService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = request.getHeader("X-Admin-Token");
        if (token == null || token.isBlank()) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && !authHeader.isBlank()) {
                token = authHeader.startsWith("Bearer ") ? authHeader.substring(7).trim() : authHeader.trim();
            }
        }

        // 🔒 强制拦截所有受保护后台接口：缺失请求头 Token 或会话无效一律拒绝，消除空 Header 绕过隐患
        if (token == null || token.isBlank() || !adminAuthService.checkAuth(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"error\":\"会话已过期或未授权，请重新登录\"}");
            return false;
        }

        return true;
    }
}

