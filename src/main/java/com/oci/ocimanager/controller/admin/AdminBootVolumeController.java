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
 * 启动盘管理接口：引导卷列表/分离/删除/扩容。原属 AdminController，URL 保持不变。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminBootVolumeController {

    @Autowired private OciCloudService ociCloudService;
    @Autowired private AdminAuthService adminAuthService;

    private boolean checkAuth(String token) {
        return adminAuthService.checkAuth(token);
    }

    @PostMapping("/boot-volumes/list")
    public Map<String, Object> listBootVolumes(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "鉴权失败");
        try {
            return Map.of("success", true, "data", ociCloudService.getBootVolumes(payload.get("account")));
        } catch (Exception e) {
            return Map.of("success", false, "error", "获取引导卷失败: " + e.getMessage());
        }
    }

    @PostMapping("/boot-volumes/detach")
    public Map<String, Object> detachBootVolume(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false);
        boolean ok = ociCloudService.detachBootVolume(payload.get("attachmentId"), payload.get("account"));
        return Map.of("success", ok, "error", ok ? "" : "API 分离调用失败，请确保无正在进行的任务");
    }

    @PostMapping("/boot-volumes/delete")
    public Map<String, Object> deleteBootVolume(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false);
        boolean ok = ociCloudService.deleteBootVolume(payload.get("bootVolumeId"), payload.get("account"));
        return Map.of("success", ok, "error", ok ? "" : "API 删除调用失败，请确保卷是未附加状态且并非刚刚解除附加 (需等待Oracle处理)");
    }

    @PostMapping("/boot-volumes/expand")
    public Map<String, Object> expandBootVolume(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false);
        try {
            Long size = Long.parseLong(payload.get("size"));
            boolean ok = ociCloudService.expandBootVolume(payload.get("bootVolumeId"), size, payload.get("account"));
            return Map.of("success", ok, "error", ok ? "" : "API 扩容调用失败");
        } catch (Exception e) {
            return Map.of("success", false, "error", "扩容请求异常: " + e.getMessage());
        }
    }
}
