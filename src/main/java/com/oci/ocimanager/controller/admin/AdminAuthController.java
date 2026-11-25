package com.oci.ocimanager.controller.admin;

import com.oci.ocimanager.service.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 后台鉴权接口：登录前公开配置探测、验证码签发、登录、二次确认(Step-Up)与登出。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    @Autowired private AdminAuthService adminAuthService;

    @RequestMapping(value = "/auth-config", method = {RequestMethod.GET, RequestMethod.POST})
    public Map<String, Object> authConfig() {
        return adminAuthService.getPublicAuthConfig();
    }

    @PostMapping("/send-code")
    public Map<String, Object> sendCode(HttpServletRequest request) {
        return adminAuthService.sendCode(adminAuthService.resolveClientIp(request));
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody(required = false) Map<String, String> payload, HttpServletRequest request) {
        String username = payload != null ? payload.get("username") : null;
        String password = payload != null ? payload.get("password") : null;
        if (password == null && payload != null) {
            password = payload.get("code");
        }
        return adminAuthService.login(username, password, adminAuthService.resolveClientIp(request));
    }

    @PostMapping("/step-up")
    public Map<String, Object> stepUp(@RequestBody(required = false) Map<String, Object> payload, HttpServletRequest request) {
        String token = adminAuthService.resolveToken(request, payload);
        if (!adminAuthService.checkAuth(token)) {
            return Map.of("success", false, "error", "会话已过期，请重新登录");
        }
        String secret = null;
        if (payload != null) {
            Object raw = payload.get("stepUpSecret");
            if (raw == null) raw = payload.get("password");
            if (raw == null) raw = payload.get("code");
            if (raw != null) secret = String.valueOf(raw);
        }
        if (secret == null || secret.isBlank()) {
            Map<String, Object> res = new HashMap<>();
            res.put("success", false);
            res.put("stepUpRequired", true);
            res.put("error", "请输入管理员密码或动态码");
            return res;
        }
        Map<String, Object> confirmed = adminAuthService.confirmStepUpCredential(secret, adminAuthService.resolveClientIp(request));
        if (Boolean.TRUE.equals(confirmed.get("success"))) {
            adminAuthService.markStepUp(token);
            return Map.of("success", true);
        }
        Map<String, Object> denied = new HashMap<>(confirmed);
        denied.put("stepUpRequired", true);
        return denied;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> payload) {
        String token = adminAuthService.resolveToken(request, payload);
        boolean ok = adminAuthService.logout(token);
        return Map.of("success", true, "message", ok ? "已成功退出登录并注销服务端会话" : "会话已关闭");
    }
}
