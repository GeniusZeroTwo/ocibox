package com.oci.ocimanager.service;

import com.oci.ocimanager.entity.SystemNotification;
import com.oci.ocimanager.repository.SystemNotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一系统运维消息与通知服务：
 * 负责本地 SQLite 消息持久化、Web 控制台实时推送，以及在 Telegram 已配置时的 TG 转发。
 */
@Service
public class NotificationService {

    private static final int MAX_NOTIFICATIONS_RETENTION = 300;

    @Autowired
    private SystemNotificationRepository notificationRepo;

    @Autowired(required = false)
    @Lazy
    private TelegramBotService telegramBotService;

    @Value("${app.admin-id:}")
    private String adminId;

    /**
     * 发送结构化系统消息
     *
     * @param title    消息简明标题
     * @param content  消息正文内容 (支持 Markdown / HTML 格式)
     * @param level    消息级别：INFO, SUCCESS, WARNING, DANGER
     * @param category 业务分类：TRAFFIC, INSTANCE, SNIPER, SYSTEM, SECURITY
     * @return 持久化后的实体
     */
    public SystemNotification send(String title, String content, String level, String category) {
        if (title == null || title.isBlank()) {
            title = "系统运维通知";
        }
        if (content == null) {
            content = "";
        }

        // 1. 本地持久化至 SQLite 数据库
        SystemNotification notice = new SystemNotification(title, content, level, category);
        try {
            notice = notificationRepo.save(notice);
            // 异步触发旧数据滚动清理，保证单表轻量化
            triggerAsyncRetentionCleanup();
        } catch (Exception e) {
            System.err.println("⚠️ [NotificationService] 消息入库失败: " + e.getMessage());
        }

        // 2. 若配置了 Telegram 且机器人正在运行，则同时推送到 TG
        pushToTelegramIfAvailable(title, content);

        return notice;
    }

    /**
     * 智能自适应发送：自动根据文本中的 Emoji 与关键词推断标题、级别与业务分类
     *
     * @param rawMessage 原始文本/HTML 消息
     * @return 持久化后的通知实体
     */
    public SystemNotification sendAuto(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return null;
        }

        String title = extractTitle(rawMessage);
        String level = extractLevel(rawMessage);
        String category = extractCategory(rawMessage);

        return send(title, rawMessage, level, category);
    }

    /**
     * 将消息同步推送到 Telegram (如果已配置且在线)
     */
    private void pushToTelegramIfAvailable(String title, String content) {
        if (telegramBotService == null || !telegramBotService.isBotRunning()) {
            return;
        }
        if (adminId == null || adminId.isBlank()) {
            return;
        }

        try {
            long chatId = Long.parseLong(adminId.trim());
            // 如果内容本身包含 HTML 标签
            if (content.contains("<") && content.contains(">")) {
                telegramBotService.sendHtmlText(chatId, content);
            } else {
                telegramBotService.sendText(chatId, content);
            }
        } catch (Exception e) {
            System.err.println("ℹ️ [NotificationService] 转发 Telegram 提示: " + e.getMessage());
        }
    }

    /**
     * 异步清理超出最大保留上限的历史消息
     */
    private void triggerAsyncRetentionCleanup() {
        CompletableFuture.runAsync(() -> {
            try {
                long totalCount = notificationRepo.count();
                if (totalCount > MAX_NOTIFICATIONS_RETENTION) {
                    List<Long> keepIds = notificationRepo.findTopIds(PageRequest.of(0, MAX_NOTIFICATIONS_RETENTION));
                    if (!keepIds.isEmpty()) {
                        notificationRepo.deleteOldRecordsNotIn(keepIds);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    // ==================== 查询与管理方法 ====================

    public long getUnreadCount() {
        try {
            return notificationRepo.countByIsReadFalse();
        } catch (Exception e) {
            return 0;
        }
    }

    public List<Map<String, Object>> getLatestUnreadNotices(int limit) {
        Pageable pageable = PageRequest.of(0, Math.max(1, limit));
        List<SystemNotification> list = notificationRepo.findByIsReadFalseOrderByCreatedAtDesc(pageable);
        return formatNotificationList(list);
    }

    public Map<String, Object> listNotifications(int page, int size, String category, boolean unreadOnly) {
        int pageIndex = Math.max(0, page);
        int pageSize = Math.min(100, Math.max(1, size));
        Pageable pageable = PageRequest.of(pageIndex, pageSize);

        List<SystemNotification> list;
        boolean hasCategory = category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category.trim());

        if (unreadOnly) {
            if (hasCategory) {
                list = notificationRepo.findByCategoryAndIsReadFalseOrderByCreatedAtDesc(category.trim().toUpperCase(), pageable);
            } else {
                list = notificationRepo.findByIsReadFalseOrderByCreatedAtDesc(pageable);
            }
        } else {
            if (hasCategory) {
                list = notificationRepo.findByCategoryOrderByCreatedAtDesc(category.trim().toUpperCase(), pageable);
            } else {
                list = notificationRepo.findAllByOrderByCreatedAtDesc(pageable);
            }
        }

        long unreadCount = getUnreadCount();
        long totalCount = notificationRepo.count();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("unreadCount", unreadCount);
        result.put("totalCount", totalCount);
        result.put("page", pageIndex);
        result.put("size", pageSize);
        result.put("list", formatNotificationList(list));
        return result;
    }

    public boolean markAsRead(Long id) {
        try {
            if (id == null || id <= 0) {
                notificationRepo.markAllAsRead();
            } else {
                notificationRepo.markAsReadById(id);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public int markAllAsRead() {
        try {
            return notificationRepo.markAllAsRead();
        } catch (Exception e) {
            return 0;
        }
    }

    public int clearRead() {
        try {
            return notificationRepo.deleteAllRead();
        } catch (Exception e) {
            return 0;
        }
    }

    public int clearAll() {
        try {
            long count = notificationRepo.count();
            notificationRepo.deleteAll();
            return (int) count;
        } catch (Exception e) {
            return 0;
        }
    }

    private List<Map<String, Object>> formatNotificationList(List<SystemNotification> list) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (list == null) return result;
        for (SystemNotification item : list) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", item.getId());
            map.put("title", item.getTitle());
            map.put("content", item.getContent());
            map.put("level", item.getLevel());
            map.put("category", item.getCategory());
            map.put("isRead", item.isRead());
            map.put("createdAt", item.getFormattedCreatedAt());
            result.add(map);
        }
        return result;
    }

    // ==================== 智能解析工具 ====================

    private static final Pattern TITLE_BRACKET_PATTERN = Pattern.compile("【(.*?)】");
    private static final Pattern TITLE_HTML_PATTERN = Pattern.compile("<b>(.*?)</b>", Pattern.CASE_INSENSITIVE);

    private String extractTitle(String text) {
        // 尝试从 【...】 提取
        Matcher m1 = TITLE_BRACKET_PATTERN.matcher(text);
        if (m1.find()) {
            return "【" + m1.group(1).trim() + "】";
        }
        // 尝试从 <b>...</b> 提取
        Matcher m2 = TITLE_HTML_PATTERN.matcher(text);
        if (m2.find()) {
            String bText = m2.group(1).replaceAll("<[^>]+>", "").trim();
            if (!bText.isBlank() && bText.length() <= 30) {
                return bText;
            }
        }
        // 取首行
        String[] lines = text.split("\n");
        for (String line : lines) {
            String clean = line.replaceAll("[*#`]", "").trim();
            if (!clean.isBlank()) {
                return clean.length() > 30 ? clean.substring(0, 30) + "..." : clean;
            }
        }
        return "系统运维消息";
    }

    private String extractLevel(String text) {
        String lower = text.toLowerCase();
        if (text.contains("🛑") || lower.contains("最高警报") || lower.contains("熔断") || lower.contains("切断") || text.contains("❌")) {
            return "DANGER";
        }
        if (text.contains("⚠️") || lower.contains("超时") || lower.contains("预警") || lower.contains("告警") || lower.contains("失败")) {
            return "WARNING";
        }
        if (text.contains("✅") || text.contains("🚀") || text.contains("🎉") || text.contains("🟢") || lower.contains("成功") || lower.contains("达成") || lower.contains("恢复")) {
            return "SUCCESS";
        }
        return "INFO";
    }

    private String extractCategory(String text) {
        String lower = text.toLowerCase();
        if (lower.contains("流量") || lower.contains("熔断") || lower.contains("断网") || lower.contains("gb")) {
            return "TRAFFIC";
        }
        if (lower.contains("抢机") || lower.contains("捡漏") || lower.contains("抢占") || lower.contains("任务")) {
            return "SNIPER";
        }
        if (lower.contains("实例") || lower.contains("开机") || lower.contains("自愈") || lower.contains("重装") || lower.contains("引导卷")) {
            return "INSTANCE";
        }
        if (lower.contains("passkey") || lower.contains("密钥") || lower.contains("登录") || lower.contains("安全")) {
            return "SECURITY";
        }
        return "SYSTEM";
    }
}
