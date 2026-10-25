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
 * 配额管理接口：免费层配额注入/解除、配额模板读写。原属 AdminController，URL 保持不变。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminQuotaController {

    @Autowired private OciCloudService ociCloudService;
    @Autowired private AdminAuthService adminAuthService;

    private boolean checkAuth(String token) {
        return adminAuthService.checkAuth(token);
    }

    @PostMapping("/quota/inject")
    public Map<String, Object> injectQuota(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "会话已过期，请重新登录");
        try {
            String account = payload.get("account");
            if (account == null || account.trim().isEmpty()) return Map.of("success", false, "error", "账号名不能为空");
            ociCloudService.injectFreeTierQuota(account);
            return Map.of("success", true);
        } catch (Exception e) {
            return com.oci.ocimanager.controller.ApiErrors.fail("防扣费配额注入失败", e);
        }
    }

    @PostMapping("/quota/delete")
    public Map<String, Object> deleteQuota(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "会话已过期，请重新登录");
        try {
            String account = payload.get("account");
            if (account == null || account.trim().isEmpty()) return Map.of("success", false, "error", "账号名不能为空");
            ociCloudService.deleteFreeTierQuota(account);
            return Map.of("success", true);
        } catch (Exception e) {
            return com.oci.ocimanager.controller.ApiErrors.fail("解除配额锁定失败", e);
        }
    }

    @PostMapping("/quota/template/get")
    public Map<String, Object> getQuotaTemplate(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "会话已过期，请重新登录");
        try {
            String template = ociCloudService.getQuotaTemplate();
            return Map.of("success", true, "template", template);
        } catch (Exception e) {
            return com.oci.ocimanager.controller.ApiErrors.fail("读取配额模板失败", e);
        }
    }

    @PostMapping("/quota/template/save")
    public Map<String, Object> saveQuotaTemplate(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "会话已过期，请重新登录");
        try {
            String template = payload.get("template");
            if (template == null || template.trim().isEmpty()) {
                return Map.of("success", false, "error", "配额模板内容不能为空");
            }
            ociCloudService.saveQuotaTemplate(template);
            return Map.of("success", true);
        } catch (Exception e) {
            return com.oci.ocimanager.controller.ApiErrors.fail("保存模板失败", e);
        }
    }
}
