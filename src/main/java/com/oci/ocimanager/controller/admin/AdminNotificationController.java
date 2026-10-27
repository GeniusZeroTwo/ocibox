package com.oci.ocimanager.controller.admin;

import com.oci.ocimanager.service.AdminAuthService;
import com.oci.ocimanager.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 站内消息与通知中心接口
 */
@RestController
@RequestMapping("/api/admin/notifications")
public class AdminNotificationController {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private AdminAuthService adminAuthService;

    private boolean checkAuth(String token) {
        return adminAuthService.checkAuth(token);
    }

    /**
     * 获取系统通知列表（支持分页、按分类过滤、按未读状态过滤）
     */
    @PostMapping("/list")
    public Map<String, Object> listNotifications(@RequestBody Map<String, Object> payload) {
        String token = (String) payload.get("token");
        if (!checkAuth(token)) {
            return Map.of("success", false, "error", "会话已过期，请重新登录");
        }

        int page = payload.get("page") instanceof Number ? ((Number) payload.get("page")).intValue() : 0;
        int size = payload.get("size") instanceof Number ? ((Number) payload.get("size")).intValue() : 20;
        String category = (String) payload.get("category");
        boolean unreadOnly = Boolean.TRUE.equals(payload.get("unreadOnly"));

        return notificationService.listNotifications(page, size, category, unreadOnly);
    }

    /**
     * 标记单条通知或全部通知为已读
     */
    @PostMapping("/read")
    public Map<String, Object> markAsRead(@RequestBody Map<String, Object> payload) {
        String token = (String) payload.get("token");
        if (!checkAuth(token)) {
            return Map.of("success", false, "error", "会话已过期，请重新登录");
        }

        Long id = null;
        if (payload.get("id") instanceof Number) {
            id = ((Number) payload.get("id")).longValue();
        }

        boolean ok = notificationService.markAsRead(id);
        long unreadCount = notificationService.getUnreadCount();

        return Map.of(
            "success", ok,
            "unreadCount", unreadCount
        );
    }

    /**
     * 清空通知（已读通知或全部通知）
     */
    @PostMapping("/clear")
    public Map<String, Object> clearNotifications(@RequestBody Map<String, Object> payload) {
        String token = (String) payload.get("token");
        if (!checkAuth(token)) {
            return Map.of("success", false, "error", "会话已过期，请重新登录");
        }

        String type = (String) payload.getOrDefault("type", "read");
        int deleted;
        if ("all".equalsIgnoreCase(type)) {
            deleted = notificationService.clearAll();
        } else {
            deleted = notificationService.clearRead();
        }

        return Map.of(
            "success", true,
            "deletedCount", deleted,
            "unreadCount", notificationService.getUnreadCount()
        );
    }
}
