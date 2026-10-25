package com.oci.ocimanager.controller.admin;

import com.oci.ocimanager.service.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 后台鉴权接口：验证码签发与登录。原属 AdminController，URL 保持不变。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    @Autowired private AdminAuthService adminAuthService;

    @PostMapping("/send-code")
    public Map<String, Object> sendCode(HttpServletRequest request) {
        return adminAuthService.sendCode(adminAuthService.resolveClientIp(request));
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> payload, HttpServletRequest request) {
        String username = payload != null ? payload.get("username") : null;
        String password = payload != null ? payload.get("password") : null;
        if (password == null && payload != null) {
            password = payload.get("code");
        }
        return adminAuthService.login(username, password, adminAuthService.resolveClientIp(request));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(
            @RequestHeader(value = "X-Admin-Token", required = false) String xAdminToken,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, String> payload) {
        String token = xAdminToken;
        if ((token == null || token.isBlank()) && authHeader != null && !authHeader.isBlank()) {
            token = authHeader.startsWith("Bearer ") ? authHeader.substring(7).trim() : authHeader.trim();
        }
        if ((token == null || token.isBlank()) && payload != null) {
            token = payload.get("token");
        }
        boolean ok = adminAuthService.logout(token);
        return Map.of("success", true, "message", ok ? "已成功退出登录并注销服务端会话" : "会话已关闭");
    }
}
