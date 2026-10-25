package com.oci.ocimanager.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 🛡️ 核心安全响应头过滤器
 * 为所有 HTTP 响应注入安全防御响应头，防御 Clickjacking（点击劫持）、MIME 嗅探、凭据与来源泄露等风险。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 防御点击劫持 (Clickjacking)
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        // 防御 MIME 类型混淆嗅探攻击
        response.setHeader("X-Content-Type-Options", "nosniff");
        // 防御敏感 Referrer 泄露
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        // 限制浏览器不必要的敏感功能权限
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        // script-src 保留 unsafe-eval：页面用 Vue 运行时编译模板（Function(...)），没有预编译步骤。
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; base-uri 'self'; frame-ancestors 'self'; form-action 'self'; "
                        + "object-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                        + "script-src 'self' 'unsafe-eval'; connect-src 'self'");

        filterChain.doFilter(request, response);
    }
}
