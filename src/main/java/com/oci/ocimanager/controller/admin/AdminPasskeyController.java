package com.oci.ocimanager.controller.admin;

import com.oci.ocimanager.service.AdminAuthService;
import com.oci.ocimanager.service.AdminPasskeyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 🔐 管理员 WebAuthn 通行密钥 (Passkey) 接口控制器
 * 严格实施双重边界鉴权校验，非登录态严禁访问任何密钥管理接口
 */
@RestController
@RequestMapping("/api/admin/passkey")
public class AdminPasskeyController {

    @Autowired private AdminPasskeyService adminPasskeyService;
    @Autowired private AdminAuthService adminAuthService;

    private boolean checkAuth(String token) {
        return adminAuthService.checkAuth(token);
    }

    /**
     * 1. 获取登录认证配置 (无需登录态，公开接口)
     */
    @PostMapping("/login/options")
    public Map<String, Object> getLoginOptions(@RequestBody(required = false) Map<String, Object> payload, HttpServletRequest request) {
        String rpId = resolveSafeRpId(request, payload);
        return adminPasskeyService.generateLoginOptions(rpId, request);
    }

    /**
     * 2. 校验登录凭证签名并签发 Token、推送 TG 通知 (无需登录态，公开接口)
     */
    @PostMapping("/login/verify")
    public Map<String, Object> verifyLogin(@RequestBody(required = false) Map<String, Object> payload, HttpServletRequest request) {
        return adminPasskeyService.verifyLogin(payload, request);
    }

    /**
     * 3. 获取注册配置 (需要管理员 Token，双重严格鉴权)
     */
    @PostMapping("/register/options")
    public Map<String, Object> getRegisterOptions(
            @RequestBody(required = false) Map<String, Object> payload,
            HttpServletRequest request) {
        String token = adminAuthService.resolveToken(request, payload);
        if (!checkAuth(token)) {
            return Map.of("success", false, "error", "请先登录后再绑定通行密钥");
        }
        String rpId = resolveSafeRpId(request, payload);
        String username = payload != null && payload.get("username") != null && !payload.get("username").toString().isBlank()
                ? payload.get("username").toString().trim() : adminAuthService.getConfiguredAdminUsername();
        String stepUpSecret = null;
        if (payload != null) {
            Object secret = payload.get("stepUpSecret");
            if (secret == null) secret = payload.get("password");
            if (secret != null) stepUpSecret = secret.toString();
        }
        return adminPasskeyService.generateRegisterOptions(token, rpId, username, stepUpSecret, request);
    }

    /**
     * 4. 校验注册凭证并保存 (需要管理员 Token，双重严格鉴权)
     */
    @PostMapping("/register/verify")
    public Map<String, Object> verifyRegister(
            @RequestBody(required = false) Map<String, Object> payload,
            HttpServletRequest request) {
        String token = adminAuthService.resolveToken(request, payload);
        if (!checkAuth(token)) {
            return Map.of("success", false, "error", "未授权访问：请先登录管理员后台！");
        }
        return adminPasskeyService.verifyAndSaveRegistration(token, payload, request);
    }

    /**
     * 5. 获取已注册的通行密钥列表 (需要管理员 Token，双重严格鉴权)
     */
    @PostMapping("/list")
    public Map<String, Object> listPasskeys(
            @RequestBody(required = false) Map<String, Object> payload,
            HttpServletRequest request) {
        String token = adminAuthService.resolveToken(request, payload);
        if (!checkAuth(token)) {
            return Map.of("success", false, "error", "会话已过期或未登录，请重新登录！");
        }
        return adminPasskeyService.listPasskeys(token);
    }

    /**
     * 6. 删除通行密钥 (需要管理员 Token，双重严格鉴权)
     */
    @PostMapping("/delete")
    public Map<String, Object> deletePasskey(
            @RequestBody(required = false) Map<String, Object> payload,
            HttpServletRequest request) {
        String token = adminAuthService.resolveToken(request, payload);
        if (!checkAuth(token)) {
            return Map.of("success", false, "error", "会话已过期或未登录，请重新登录！");
        }
        Long id = parsePasskeyId(payload);
        if (id == null) {
            return Map.of("success", false, "error", "参数 id 无效或格式不合法");
        }
        return adminPasskeyService.deletePasskey(token, id);
    }

    /**
     * 7. 重命名通行密钥 (需要管理员 Token，双重严格鉴权)
     */
    @PostMapping("/update-name")
    public Map<String, Object> renamePasskey(
            @RequestBody(required = false) Map<String, Object> payload,
            HttpServletRequest request) {
        String token = adminAuthService.resolveToken(request, payload);
        if (!checkAuth(token)) {
            return Map.of("success", false, "error", "会话已过期或未登录，请重新登录！");
        }
        Long id = parsePasskeyId(payload);
        if (id == null) {
            return Map.of("success", false, "error", "参数 id 无效或格式不合法");
        }
        String name = payload != null && payload.get("name") != null ? String.valueOf(payload.get("name")) : null;
        return adminPasskeyService.renamePasskey(token, id, name);
    }

    private Long parsePasskeyId(Map<String, Object> payload) {
        if (payload == null || payload.get("id") == null) return null;
        Object idObj = payload.get("id");
        if (idObj instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(idObj.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 只认浏览器发来的 Host。客户端 rpId 必须与 Host 完全一致，不接受父域后缀，也不读 X-Forwarded-Host。
     */
    private String resolveSafeRpId(HttpServletRequest request, Map<String, Object> payload) {
        String host = request != null ? request.getHeader("Host") : null;
        if (host == null || host.isBlank()) {
            host = request != null ? request.getServerName() : "localhost";
        }
        host = stripHost(host);
        if (host == null || host.isBlank()) {
            host = "localhost";
        }
        host = host.toLowerCase(java.util.Locale.ROOT);

        if (payload != null && payload.get("rpId") != null) {
            String candidate = stripHost(payload.get("rpId").toString());
            if (candidate != null && candidate.equalsIgnoreCase(host)) {
                return candidate.toLowerCase(java.util.Locale.ROOT);
            }
        }
        return host;
    }

    private static String stripHost(String raw) {
        if (raw == null) return null;
        String host = raw.trim();
        if (host.isEmpty()) return null;
        if (host.contains(",")) {
            host = host.split(",")[0].trim();
        }
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end > 1) return host.substring(1, end).trim();
            return host;
        }
        int colon = host.indexOf(':');
        if (colon > 0 && host.indexOf(':', colon + 1) < 0) {
            host = host.substring(0, colon).trim();
        }
        return host.isEmpty() ? null : host;
    }
}
