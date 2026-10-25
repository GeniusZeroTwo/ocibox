package com.oci.ocimanager.controller.admin;

import com.oci.ocimanager.service.AdminAuthService;
import com.oci.ocimanager.service.OciCloudService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 网络管理接口：一键修复路由表、全端口放行。原属 AdminController，URL 保持不变。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminNetworkController {

    @Autowired private OciCloudService ociCloudService;
    @Autowired private AdminAuthService adminAuthService;

    private boolean checkAuth(String token) {
        return adminAuthService.checkAuth(token);
    }

    @PostMapping("/route/fix")
    public Map<String, Object> fixRouteTables(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "会话已过期，请重新登录");
        try {
            String account = payload.get("account");
            if (account == null || account.trim().isEmpty()) return Map.of("success", false, "error", "账号名不能为空");
            ociCloudService.fixRouteTables(account);
            return Map.of("success", true);
        } catch (Exception e) {
            return Map.of("success", false, "error", "一键修复路由表失败: " + e.getMessage());
        }
    }

    @PostMapping("/network/open-ports")
    public Map<String, Object> openAllPorts(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false);
        try {
            String account = payload.get("account");
            ociCloudService.openAllPorts(account);
            return Map.of("success", true);
        } catch (Exception e) {
            return Map.of("success", false, "error", "执行异常: " + e.getMessage());
        }
    }
}
