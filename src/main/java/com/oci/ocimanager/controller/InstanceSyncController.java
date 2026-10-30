package com.oci.ocimanager.controller;

import com.oci.ocimanager.service.AdminAuthService;
import com.oci.ocimanager.service.OciCloudService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@RestController
@RequestMapping("/api/admin")
public class InstanceSyncController {

    private static final int MAX_SYNC_LOCKS = 1000;

    @Autowired
    private OciCloudService ociCloudService;

    @Autowired
    private AdminAuthService adminAuthService;

    private final ConcurrentHashMap<String, ReentrantLock> instanceSyncLocks = new ConcurrentHashMap<>();

    @PostMapping("/instance-sync")
    public Map<String, Object> syncSingleInstance(HttpServletRequest request, @RequestBody(required = false) Map<String, String> payload) {
        // 🔒 先鉴权再处理任何入参，支持 Header 与 Body 双重解析，避免未授权者通过不同报错区分请求状态。
        if (!checkAdminAuth(request, payload)) {
            return Map.of("success", false, "error", "会话已过期，请重新登录");
        }
        if (payload == null) {
            return Map.of("success", false, "error", "缺少请求参数");
        }

        String ocid = payload.get("ocid");
        if (ocid == null || ocid.isBlank()) {
            return Map.of("success", false, "error", "缺少实例 OCID");
        }

        String account = payload.get("account");

        if (instanceSyncLocks.size() > MAX_SYNC_LOCKS) {
            instanceSyncLocks.entrySet().removeIf(e -> e.getValue() == null || !e.getValue().isLocked());
        }

        ReentrantLock lock = instanceSyncLocks.computeIfAbsent(ocid, key -> new ReentrantLock());
        if (!lock.tryLock()) {
            return Map.of("success", false, "error", "该机器正在同步中，请稍后再试");
        }

        try {
            Map<String, String> info = ociCloudService.refreshSingleInstance(ocid, account);
            if ("true".equalsIgnoreCase(info.get("terminated")) || "TERMINATED".equalsIgnoreCase(info.get("state"))) {
                return Map.of(
                    "success", true,
                    "ocid", ocid,
                    "terminated", true,
                    "message", "该机器在甲骨文云端已不存在或已被终止，已从列表中移除"
                );
            }

            return Map.of("success", true, "ocid", ocid, "instance", info);
        } catch (Exception e) {
            return com.oci.ocimanager.controller.ApiErrors.fail("实例同步失败", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 🌟 统一委托给 AdminAuthService 进行会话校验与续期 (兼容 Header 与 Body)
     */
    private boolean checkAdminAuth(HttpServletRequest request, Map<String, String> payload) {
        return adminAuthService.checkAuth(request, payload);
    }
}
