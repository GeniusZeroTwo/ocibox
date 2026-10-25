package com.oci.ocimanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oci.ocimanager.config.OciProperties;
import com.oci.ocimanager.entity.SystemConfig;
import com.oci.ocimanager.entity.TrafficLimit;
import com.oci.ocimanager.repository.SystemConfigRepository;
import com.oci.ocimanager.repository.TrafficLimitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 个人专属 Telegram 运维机器人（零商业租户/无配额限制/独占管理）
 */
@Service
public class TelegramBotService implements LongPollingSingleThreadUpdateConsumer {

    @Configuration
    public static class GlobalJacksonConfig {
        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Value("${app.bot-token:}")
    private String token;

    @Value("${app.admin-id:}")
    private String adminId;

    @Value("${app.backup-password:}")
    private String backupPassword;

    public static final ConcurrentHashMap<String, String> TG_USER_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<String, String> TG_URL_MAP = new ConcurrentHashMap<>();

    private static final String TOTAL_SYSTEM_IP_CHANGES_KEY = "TOTAL_SYSTEM_IP_CHANGES";

    @Autowired private SystemConfigRepository systemConfigRepo;
    @Autowired private OciCloudService ociCloudService;
    @Autowired private GitHubService gitHubService;
    @Autowired private TrafficLimitRepository trafficLimitRepo;
    @Autowired private OciProperties ociProperties;
    @Autowired private AccountYamlService accountYamlService;
    @Autowired private LocalCacheService redisTemplate;

    private TelegramClient telegramClient;
    private TelegramBotsLongPollingApplication botsApplication;
    private boolean isBotRunning = false;

    private final ExecutorService botWorker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "tg-personal-bot");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        if (token == null || token.isBlank() || "YOUR_TELEGRAM_BOT_TOKEN".equals(token.trim())) {
            this.isBotRunning = false;
            System.out.println("ℹ️ [Telegram Bot] 未配置 Token 或保持默认占位符，机器人未启动（可在系统配置或一键部署脚本中绑定）。");
            return;
        }

        try {
            this.telegramClient = new OkHttpTelegramClient(getBotToken());
            this.botsApplication = new TelegramBotsLongPollingApplication();
            this.botsApplication.registerBot(getBotToken(), this);
            this.isBotRunning = true;
            System.out.println("✅ [Telegram Bot] 个人管理机器人启动成功！管理员 ID: " + adminId);
        } catch (Exception e) {
            this.isBotRunning = false;
            System.err.println("⚠️ [Telegram Bot] 初始化失败: " + e.getMessage() + "，系统将在无机器人模式下正常运行。");
        }
    }

    public boolean isBotRunning() {
        return this.isBotRunning;
    }

    @PreDestroy
    public void destroy() {
        if (this.botsApplication != null) {
            try {
                this.botsApplication.close();
            } catch (Exception ignored) {}
        }
        botWorker.shutdown();
    }

    public void shutdownBotExecutor() {
        destroy();
    }

    public String getBotToken() {
        return token != null ? token.trim() : "";
    }

    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    public String getBotUsername() {
        return "OCI_Personal_Bot";
    }

    public static String extractTelegramUsername(org.telegram.telegrambots.meta.api.objects.User user) {
        if (user == null) return null;
        if (user.getUserName() != null && !user.getUserName().isBlank()) {
            return "@" + user.getUserName().trim().replace("@", "");
        }
        String fn = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String ln = user.getLastName() != null ? user.getLastName().trim() : "";
        String fullName = (fn + " " + ln).trim();
        return fullName.isBlank() ? null : fullName;
    }

    public static String extractTelegramUserUrl(org.telegram.telegrambots.meta.api.objects.User user) {
        if (user == null) return null;
        if (user.getUserName() != null && !user.getUserName().isBlank()) {
            return "https://t.me/" + user.getUserName().trim().replace("@", "");
        }
        return "tg://user?id=" + user.getId();
    }

    public String getOrFetchTelegramUsername(String tgId) {
        if (tgId == null || tgId.isBlank()) return null;
        return TG_USER_MAP.get(tgId);
    }

    public static String cleanInstanceDisplayName(String rawName, String account) {
        if (rawName == null) return "未命名实例";
        if (account != null && !account.isBlank()) {
            rawName = rawName.replace("[" + account + "] ", "").replace("[" + account + "]", "");
        }
        return rawName.trim();
    }

    public static String generateRandomPassword(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private boolean isAuthorizedAdmin(long chatId) {
        if (adminId == null || adminId.isBlank()) return false;
        return adminId.trim().equals(String.valueOf(chatId).trim());
    }

    @Override
    public void consume(Update update) {
        botWorker.submit(() -> {
            try {
                if (update.hasMessage() && update.getMessage().hasText()) {
                    handleTextMessage(update.getMessage().getChatId(), update.getMessage().getText().trim());
                } else if (update.hasCallbackQuery()) {
                    handleCallbackQuery(update.getCallbackQuery());
                }
            } catch (Exception e) {
                System.err.println("❌ 处理 Telegram 更新异常: " + e.getMessage());
            }
        });
    }

    private void handleTextMessage(long chatId, String text) {
        if (!isAuthorizedAdmin(chatId)) {
            sendText(chatId, "🔒 抱歉，当前机器人为个人私有管理面板专属服务，未对公开放。");
            return;
        }

        if ("/start".equals(text) || "/menu".equals(text)) {
            renderStartView(chatId, null);
        } else if ("/list".equals(text)) {
            renderInstanceListPage(chatId, null, 0);
        } else if ("/traffic".equals(text)) {
            sendTrafficOverview(chatId, null);
        } else if ("/sync".equals(text)) {
            sendText(chatId, "🔄 正在同步全账号与实例，请稍候...");
            try {
                Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
                int accCount = ociProperties.getAccounts() != null ? ociProperties.getAccounts().size() : 0;
                sendText(chatId, "✅ 同步完成！共纳管 " + accCount + " 个甲骨文账号，" + instances.size() + " 台实例。");
            } catch (Exception e) {
                sendText(chatId, "❌ 同步失败: " + e.getMessage());
            }
        } else if ("/backup".equals(text)) {
            sendText(chatId, "🗄️ 正在触发系统安全备份...");
            try {
                ScheduledTasksService tasks = ScheduledTasksService.getInstance();
                if (tasks != null) {
                    tasks.dailyBackup();
                    sendText(chatId, "✅ 备份完成！已同步到配置的目标仓库。");
                } else {
                    sendText(chatId, "⚠️ 定时任务服务尚未就绪。");
                }
            } catch (Exception e) {
                sendText(chatId, "❌ 备份异常: " + e.getMessage());
            }
        } else if ("/help".equals(text)) {
            String help = "👑 <b>【OCI Personal 个人管理指令】</b>\n\n"
                    + "/start - 打开主控台菜单\n"
                    + "/list - 查看所有 OCI 云主机\n"
                    + "/traffic - 查询全账号当月出站流量\n"
                    + "/sync - 立即同步所有账号实例状态\n"
                    + "/backup - 触发 SQLite 数据库安全备份\n"
                    + "/help - 显示指令帮助";
            sendHtmlText(chatId, help);
        } else {
            renderStartView(chatId, null);
        }
    }

    private void renderStartView(long chatId, Integer messageId) {
        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        int accCount = ociProperties.getAccounts() != null ? ociProperties.getAccounts().size() : 0;
        int totalChanges = getTotalSystemChanges();

        String msg = "👑 <b>【OCI Personal 个人专属控制台】</b>\n\n"
                + "👤 <b>管理员专属服务</b>：个人独占 / 零配额限制 / 极简纯净\n"
                + "☁️ <b>纳管账号</b>: <code>" + accCount + "</code> 个\n"
                + "🖥️ <b>云服务器</b>: <code>" + instances.size() + "</code> 台\n"
                + "🔄 <b>累计更换 IP</b>: <code>" + totalChanges + "</code> 次\n\n"
                + "👇 请选择要执行的运维操作：";

        List<InlineKeyboardRow> rows = new ArrayList<>();

        InlineKeyboardRow r1 = new InlineKeyboardRow();
        r1.add(InlineKeyboardButton.builder().text("🖥️ 实例列表管理").callbackData("page_0").build());
        r1.add(InlineKeyboardButton.builder().text("📊 流量监控大盘").callbackData("menu_traffic").build());
        rows.add(r1);

        InlineKeyboardRow r2 = new InlineKeyboardRow();
        r2.add(InlineKeyboardButton.builder().text("🔄 全局立即同步").callbackData("menu_sync").build());
        r2.add(InlineKeyboardButton.builder().text("🗄️ 数据库安全备份").callbackData("menu_backup").build());
        rows.add(r2);

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(rows).build();
        if (messageId != null) {
            updateMessage(chatId, messageId, msg, markup);
        } else {
            sendHtmlTextWithMarkup(chatId, msg, markup);
        }
    }

    private void renderInstanceListPage(long chatId, Integer messageId, int page) {
        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        List<Map.Entry<String, Map<String, String>>> list = new ArrayList<>(instances.entrySet());

        int pageSize = 6;
        int totalPages = Math.max(1, (int) Math.ceil((double) list.size() / pageSize));
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;

        int fromIdx = page * pageSize;
        int toIdx = Math.min(fromIdx + pageSize, list.size());

        StringBuilder sb = new StringBuilder("🖥️ <b>【实例列表】</b> (第 " + (page + 1) + "/" + totalPages + " 页，共 " + list.size() + " 台)\n\n");
        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (int i = fromIdx; i < toIdx; i++) {
            var entry = list.get(i);
            String ocid = entry.getKey();
            var info = entry.getValue();

            String accName = info.getOrDefault("account", "未知");
            String rawName = info.getOrDefault("name", "未命名");
            String cleanName = cleanInstanceDisplayName(rawName, accName);
            String state = info.getOrDefault("state", "UNKNOWN");
            String stateEmoji = "RUNNING".equalsIgnoreCase(state) ? "🟢" : ("STOPPED".equalsIgnoreCase(state) ? "🔴" : "🟡");

            String ip = ociCloudService.getCachedIp(ocid);
            if (ip == null || ip.isBlank() || ip.startsWith("待获取")) {
                ip = ociCloudService.getInstanceIp(ocid, accName);
            }
            if (ip == null || ip.isBlank()) ip = "无IP";

            String shortId = generateShortId();
            redisTemplate.opsForValue().set("oci:tg_inst:" + shortId, ocid, 2, TimeUnit.HOURS);

            InlineKeyboardRow r = new InlineKeyboardRow();
            r.add(InlineKeyboardButton.builder()
                    .text(stateEmoji + " " + cleanName + " (" + ip + ")")
                    .callbackData("inst_" + shortId)
                    .build());
            rows.add(r);
        }

        // 分页导航按钮
        InlineKeyboardRow nav = new InlineKeyboardRow();
        if (page > 0) {
            nav.add(InlineKeyboardButton.builder().text("⬅️ 上一页").callbackData("page_" + (page - 1)).build());
        }
        nav.add(InlineKeyboardButton.builder().text("🏠 返回主菜单").callbackData("menu_start").build());
        if (page < totalPages - 1) {
            nav.add(InlineKeyboardButton.builder().text("下一页 ➡️").callbackData("page_" + (page + 1)).build());
        }
        rows.add(nav);

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(rows).build();
        if (messageId != null) {
            updateMessage(chatId, messageId, sb.toString(), markup);
        } else {
            sendHtmlTextWithMarkup(chatId, sb.toString(), markup);
        }
    }

    private void renderInstanceDetail(long chatId, Integer messageId, String shortId, String ocid) {
        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        Map<String, String> info = instances.get(ocid);
        if (info == null) {
            updateMessage(chatId, messageId, "❌ 未找到实例信息，该机器可能已被终止或删除。", null);
            return;
        }

        String accName = info.getOrDefault("account", "未知");
        String rawName = info.getOrDefault("name", "未命名");
        String cleanName = cleanInstanceDisplayName(rawName, accName);
        String state = info.getOrDefault("state", "UNKNOWN");
        String shape = info.getOrDefault("shape", "未知");
        String region = info.getOrDefault("region", "未知");
        String ocpu = info.getOrDefault("ocpu", "-");
        String memory = info.getOrDefault("memory", "-");

        String ip = ociCloudService.getCachedIp(ocid);
        if (ip == null || ip.isBlank() || ip.startsWith("待获取")) {
            ip = ociCloudService.getInstanceIp(ocid, accName);
        }
        if (ip == null || ip.isBlank()) ip = "未分配";

        String ipv6 = ociCloudService.getCachedIpv6(ocid);
        if (ipv6 == null || ipv6.isBlank()) ipv6 = "无";

        String stateDisplay = "RUNNING".equalsIgnoreCase(state) ? "🟢 运行中 (RUNNING)" :
                ("STOPPED".equalsIgnoreCase(state) ? "🔴 已停止 (STOPPED)" : "🟡 " + state);

        String msg = "🖥️ <b>【实例运维详情】</b>\n\n"
                + "💻 <b>实例名称</b>: <code>" + cleanName + "</code>\n"
                + "⚡ <b>运行状态</b>: " + stateDisplay + "\n"
                + "🌐 <b>公网 IPv4</b>: <code>" + ip + "</code>\n"
                + "🌍 <b>IPv6 地址</b>: <code>" + ipv6 + "</code>\n"
                + "☁️ <b>所属账号</b>: <code>" + accName + "</code>\n"
                + "📍 <b>所在区域</b>: <code>" + region + "</code>\n"
                + "📐 <b>机型规格</b>: <code>" + shape + " (" + ocpu + "C / " + memory + "G)</code>\n\n"
                + "👇 请选择管理操作：";

        List<InlineKeyboardRow> rows = new ArrayList<>();

        InlineKeyboardRow r1 = new InlineKeyboardRow();
        r1.add(InlineKeyboardButton.builder().text("🔄 更换公网 IP").callbackData("change_ip_" + shortId).build());
        r1.add(InlineKeyboardButton.builder().text("💿 重装操作系统").callbackData("reinst_" + shortId).build());
        rows.add(r1);

        InlineKeyboardRow r2 = new InlineKeyboardRow();
        if ("RUNNING".equalsIgnoreCase(state)) {
            r2.add(InlineKeyboardButton.builder().text("🔄 重启机器").callbackData("reboot_" + shortId).build());
            r2.add(InlineKeyboardButton.builder().text("🔴 停止关机").callbackData("stop_" + shortId).build());
        } else {
            r2.add(InlineKeyboardButton.builder().text("🟢 开机启动").callbackData("start_" + shortId).build());
        }
        rows.add(r2);

        InlineKeyboardRow r3 = new InlineKeyboardRow();
        r3.add(InlineKeyboardButton.builder().text("🔙 返回列表").callbackData("page_0").build());
        rows.add(r3);

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(rows).build();
        updateMessage(chatId, messageId, msg, markup);
    }

    private void handleCallbackQuery(org.telegram.telegrambots.meta.api.objects.CallbackQuery cb) {
        long chatId = cb.getMessage().getChatId();
        int messageId = cb.getMessage().getMessageId();
        String data = cb.getData();
        String queryId = cb.getId();

        if (!isAuthorizedAdmin(chatId)) {
            answerCallback(queryId, "🔒 仅管理员有权操作", true);
            return;
        }

        if ("menu_start".equals(data)) {
            answerCallback(queryId, "主菜单", false);
            renderStartView(chatId, messageId);
        } else if ("menu_traffic".equals(data)) {
            answerCallback(queryId, "正在加载流量数据...", false);
            sendTrafficOverview(chatId, messageId);
        } else if ("menu_sync".equals(data)) {
            answerCallback(queryId, "正在同步...", false);
            try {
                Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
                answerCallback(queryId, "✅ 同步成功！共 " + instances.size() + " 台实例", true);
                renderStartView(chatId, messageId);
            } catch (Exception e) {
                answerCallback(queryId, "❌ 同步失败: " + e.getMessage(), true);
            }
        } else if ("menu_backup".equals(data)) {
            answerCallback(queryId, "正在执行安全备份...", false);
            try {
                ScheduledTasksService tasks = ScheduledTasksService.getInstance();
                if (tasks != null) {
                    tasks.dailyBackup();
                    answerCallback(queryId, "✅ 备份完成！", true);
                } else {
                    answerCallback(queryId, "⚠️ 定时任务服务未就绪", true);
                }
            } catch (Exception e) {
                answerCallback(queryId, "❌ 备份失败: " + e.getMessage(), true);
            }
        } else if (data.startsWith("page_")) {
            int page = 0;
            try {
                page = Integer.parseInt(data.replace("page_", ""));
            } catch (Exception ignored) {}
            answerCallback(queryId, "第 " + (page + 1) + " 页", false);
            renderInstanceListPage(chatId, messageId, page);
        } else if (data.startsWith("inst_")) {
            String shortId = data.replace("inst_", "");
            String ocid = redisTemplate.opsForValue().get("oci:tg_inst:" + shortId);
            if (ocid == null) {
                answerCallback(queryId, "❌ 会话超时，请重新打开列表", true);
                renderInstanceListPage(chatId, messageId, 0);
                return;
            }
            answerCallback(queryId, "加载实例信息...", false);
            renderInstanceDetail(chatId, messageId, shortId, ocid);
        } else if (data.startsWith("change_ip_")) {
            String shortId = data.replace("change_ip_", "");
            String ocid = redisTemplate.opsForValue().get("oci:tg_inst:" + shortId);
            if (ocid == null) {
                answerCallback(queryId, "❌ 会话超时", true);
                return;
            }
            handleChangeIp(chatId, messageId, queryId, shortId, ocid);
        } else if (data.startsWith("start_")) {
            String shortId = data.replace("start_", "");
            String ocid = redisTemplate.opsForValue().get("oci:tg_inst:" + shortId);
            if (ocid != null) handlePowerAction(chatId, messageId, queryId, shortId, ocid, "START");
        } else if (data.startsWith("stop_")) {
            String shortId = data.replace("stop_", "");
            String ocid = redisTemplate.opsForValue().get("oci:tg_inst:" + shortId);
            if (ocid != null) handlePowerAction(chatId, messageId, queryId, shortId, ocid, "STOP");
        } else if (data.startsWith("reboot_")) {
            String shortId = data.replace("reboot_", "");
            String ocid = redisTemplate.opsForValue().get("oci:tg_inst:" + shortId);
            if (ocid != null) handlePowerAction(chatId, messageId, queryId, shortId, ocid, "RESET");
        } else if (data.startsWith("reinst_")) {
            String shortId = data.replace("reinst_", "");
            String ocid = redisTemplate.opsForValue().get("oci:tg_inst:" + shortId);
            if (ocid != null) handleReinstallSelect(chatId, messageId, queryId, shortId, ocid);
        } else if (data.startsWith("sel_img_")) {
            handleReinstallConfirm(chatId, messageId, queryId, data);
        } else if (data.startsWith("do_reinst_")) {
            handleReinstallExecute(chatId, messageId, queryId, data);
        }
    }

    private void handleChangeIp(long chatId, int messageId, String queryId, String shortId, String ocid) {
        answerCallback(queryId, "⏳ 正在向 Oracle API 申请分配新 IP...", false);
        updateMessage(chatId, messageId, "⏳ <b>正在更换公网 IP...</b>\n\n正在解绑并释放旧 IP，重新申请分配并附加新公网 IP，这通常需要 5~15 秒，请耐心等待...", null);

        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
                Map<String, String> info = instances.get(ocid);
                String accName = info != null ? info.get("account") : null;

                String newIp = ociCloudService.changeInstanceIp(ocid, accName);
                if (newIp != null && !newIp.isBlank()) {
                    getAndIncrementTotalSystemChanges();
                    String success = "✅ <b>【公网 IP 更换成功】</b>\n\n"
                            + "💻 <b>实例名称</b>: <code>" + (info != null ? cleanInstanceDisplayName(info.get("name"), accName) : "实例") + "</code>\n"
                            + "🌐 <b>全新公网 IP</b>: <code>" + newIp + "</code>\n\n"
                            + "💡 提示：新 IP 已立即生效，请使用新 IP 重新建立 SSH 连接。";

                    List<InlineKeyboardRow> rows = new ArrayList<>();
                    InlineKeyboardRow r = new InlineKeyboardRow();
                    r.add(InlineKeyboardButton.builder().text("🔙 返回该实例").callbackData("inst_" + shortId).build());
                    r.add(InlineKeyboardButton.builder().text("🖥️ 实例列表").callbackData("page_0").build());
                    rows.add(r);

                    updateMessage(chatId, messageId, success, InlineKeyboardMarkup.builder().keyboard(rows).build());
                } else {
                    updateMessage(chatId, messageId, "❌ 更换 IP 失败：未能获取到新分配的 IP 地址。", null);
                }
            } catch (Exception e) {
                updateMessage(chatId, messageId, "❌ 更换 IP 异常: " + e.getMessage(), null);
            }
        });
    }

    private void handlePowerAction(long chatId, int messageId, String queryId, String shortId, String ocid, String action) {
        String actionName = "START".equals(action) ? "开机启动" : ("STOP".equals(action) ? "停止关机" : "重启");
        answerCallback(queryId, "⏳ 正在发送" + actionName + "指令...", false);

        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
                Map<String, String> info = instances.get(ocid);
                String accName = info != null ? info.get("account") : null;

                if ("START".equals(action)) {
                    ociCloudService.instanceAction(ocid, "START", accName);
                } else if ("STOP".equals(action)) {
                    ociCloudService.instanceAction(ocid, "STOP", accName);
                } else {
                    ociCloudService.instanceAction(ocid, "SOFTRESET", accName);
                }

                answerCallback(queryId, "✅ 已成功发送" + actionName + "指令！", true);
                renderInstanceDetail(chatId, messageId, shortId, ocid);
            } catch (Exception e) {
                answerCallback(queryId, "❌ 操作失败: " + e.getMessage(), true);
            }
        });
    }

    private void handleReinstallSelect(long chatId, int messageId, String queryId, String shortId, String ocid) {
        answerCallback(queryId, "⏳ 正在拉取官方系统镜像...", false);
        updateMessage(chatId, messageId, "⏳ 正在查询该区域支持的官方操作系统镜像列表，请稍候...", null);

        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
                Map<String, String> info = instances.get(ocid);
                if (info == null) {
                    updateMessage(chatId, messageId, "❌ 未找到实例信息。", null);
                    return;
                }

                String accName = info.get("account");
                String shape = info.get("shape");
                String region = info.get("region");
                String rawName = info.get("name");
                String cleanName = cleanInstanceDisplayName(rawName, accName);

                List<Map<String, String>> images = ociCloudService.getAvailableImages(accName, shape, region);
                if (images == null || images.isEmpty()) {
                    updateMessage(chatId, messageId, "❌ 未能获取到匹配该机型的镜像列表。", null);
                    return;
                }

                String sessId = UUID.randomUUID().toString().substring(0, 8);
                redisTemplate.opsForValue().set("oci:tg_reinst_ocid:" + sessId, ocid, 1, TimeUnit.HOURS);
                redisTemplate.opsForValue().set("oci:tg_reinst_btn:" + sessId, shortId, 1, TimeUnit.HOURS);

                List<InlineKeyboardRow> rows = new ArrayList<>();
                int maxImages = Math.min(images.size(), 12);
                for (int i = 0; i < maxImages; i++) {
                    var img = images.get(i);
                    String val = img.get("value");
                    String disp = img.get("display");
                    redisTemplate.opsForValue().set("oci:tg_reinst_val:" + sessId + ":" + i, val, 1, TimeUnit.HOURS);
                    redisTemplate.opsForValue().set("oci:tg_reinst_name:" + sessId + ":" + i, disp, 1, TimeUnit.HOURS);

                    InlineKeyboardRow r = new InlineKeyboardRow();
                    r.add(InlineKeyboardButton.builder()
                            .text("🐧 " + disp)
                            .callbackData("sel_img_" + sessId + "_" + i)
                            .build());
                    rows.add(r);
                }

                InlineKeyboardRow back = new InlineKeyboardRow();
                back.add(InlineKeyboardButton.builder().text("🔙 取消并返回").callbackData("inst_" + shortId).build());
                rows.add(back);

                String msg = "💿 <b>【选择重装操作系统】</b>\n\n"
                        + "🖥️ <b>目标实例</b>: <code>" + cleanName + "</code>\n"
                        + "⚠️ <b>注意</b>: 重装系统将格式化引导卷，机器上所有现有数据将被清空！\n\n"
                        + "👇 请选择要安装的官方镜像：";

                updateMessage(chatId, messageId, msg, InlineKeyboardMarkup.builder().keyboard(rows).build());
            } catch (Exception e) {
                updateMessage(chatId, messageId, "❌ 获取系统镜像列表失败: " + e.getMessage(), null);
            }
        });
    }

    private void handleReinstallConfirm(long chatId, int messageId, String queryId, String data) {
        String[] parts = data.split("_");
        if (parts.length < 4) return;
        String sessId = parts[2];
        String idx = parts[3];

        String ocid = redisTemplate.opsForValue().get("oci:tg_reinst_ocid:" + sessId);
        String shortId = redisTemplate.opsForValue().get("oci:tg_reinst_btn:" + sessId);
        String imgVal = redisTemplate.opsForValue().get("oci:tg_reinst_val:" + sessId + ":" + idx);
        String imgName = redisTemplate.opsForValue().get("oci:tg_reinst_name:" + sessId + ":" + idx);

        if (ocid == null || imgVal == null) {
            answerCallback(queryId, "❌ 会话超时，请重新选择", true);
            return;
        }

        answerCallback(queryId, "已选中: " + imgName, false);

        String msg = "⚠️ <b>【高危操作：系统重装最终确认】</b>\n\n"
                + "🐧 <b>选定镜像</b>: <code>" + imgName + "</code>\n"
                + "⚡ <b>注意</b>: 系统盘将完全重置，执行后不可逆！\n\n"
                + "确认要立即开始重装吗？";

        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow r = new InlineKeyboardRow();
        r.add(InlineKeyboardButton.builder().text("🔥 确认立即重装").callbackData("do_reinst_" + sessId + "_" + idx).build());
        r.add(InlineKeyboardButton.builder().text("🔙 取消").callbackData("inst_" + shortId).build());
        rows.add(r);

        updateMessage(chatId, messageId, msg, InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void handleReinstallExecute(long chatId, int messageId, String queryId, String data) {
        String[] parts = data.split("_");
        if (parts.length < 4) return;
        String sessId = parts[2];
        String idx = parts[3];

        String ocid = redisTemplate.opsForValue().get("oci:tg_reinst_ocid:" + sessId);
        String shortId = redisTemplate.opsForValue().get("oci:tg_reinst_btn:" + sessId);
        String imgVal = redisTemplate.opsForValue().get("oci:tg_reinst_val:" + sessId + ":" + idx);
        String imgName = redisTemplate.opsForValue().get("oci:tg_reinst_name:" + sessId + ":" + idx);

        if (ocid == null || imgVal == null) {
            answerCallback(queryId, "❌ 会话超时", true);
            return;
        }

        answerCallback(queryId, "🚀 开始重装...", false);
        updateMessage(chatId, messageId, "⏳ <b>正在执行系统重装...</b>\n\n正在停止实例并更换引导卷镜像为 <code>" + imgName + "</code>，预计耗时 2~5 分钟...", null);

        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
                Map<String, String> info = instances.get(ocid);
                String accName = info != null ? info.get("account") : null;
                String randomPwd = generateRandomPassword(16);
                String[] osParts = imgVal.split("\\|", 2);
                String osName = osParts[0];
                String osVersion = osParts.length > 1 ? osParts[1] : "";

                boolean ok = ociCloudService.reinstallInstance(ocid, accName, (info != null ? info.get("name") : null), osName, osVersion, null, null, randomPwd);
                if (ok) {
                    String success = "✅ <b>【系统重装任务已成功派发】</b>\n\n"
                            + "🐧 <b>安装镜像</b>: <code>" + imgName + "</code>\n"
                            + "🔑 <b>初始 root 密码</b>: <code>" + randomPwd + "</code>\n\n"
                            + "引导卷正在后台拉起开机，稍后请使用该密码建立连接。";
                    sendHtmlText(chatId, success);
                } else {
                    sendHtmlText(chatId, "❌ 系统重装失败，请在网页后台查看详细错误日志。");
                }
            } catch (Exception e) {
                sendHtmlText(chatId, "❌ 重装执行异常: " + e.getMessage());
            }
        });
    }

    private void sendTrafficOverview(long chatId, Integer messageId) {
        var accounts = ociProperties.getAccounts();
        if (accounts == null || accounts.isEmpty()) {
            String empty = "ℹ️ 当前尚未配置任何甲骨文账号。";
            if (messageId != null) updateMessage(chatId, messageId, empty, null);
            else sendHtmlText(chatId, empty);
            return;
        }

        StringBuilder sb = new StringBuilder("📊 <b>【全账号当月出站流量大盘】</b>\n\n");
        for (String accName : accounts.keySet()) {
            Double usage = ScheduledTasksService.TRAFFIC_CACHE.getOrDefault(accName, -1.0);
            TrafficLimit limit = trafficLimitRepo.findById(accName).orElse(null);
            int limitGb = (limit != null && limit.getLimitGb() != null) ? limit.getLimitGb() : 0;
            String region = accounts.get(accName).getRegion();

            sb.append("☁️ <b>").append(accName).append("</b> (<code>").append(region).append("</code>)\n");
            if (usage >= 0) {
                if (limitGb > 0) {
                    double pct = (usage / limitGb) * 100;
                    String icon = pct >= 90 ? "🔴" : (pct >= 70 ? "🟡" : "🟢");
                    sb.append("  ").append(icon).append(" 已用: <code>").append(String.format(Locale.US, "%.2f", usage)).append(" GB</code> / <code>").append(limitGb).append(" GB</code> (").append(String.format(Locale.US, "%.1f", pct)).append("%)\n");
                } else {
                    sb.append("  🟢 已用: <code>").append(String.format(Locale.US, "%.2f", usage)).append(" GB</code> / 不限额\n");
                }
            } else {
                sb.append("  ❓ 流量抓取中或未配置\n");
            }
            sb.append("\n");
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow r = new InlineKeyboardRow();
        r.add(InlineKeyboardButton.builder().text("🔄 刷新数据").callbackData("menu_traffic").build());
        r.add(InlineKeyboardButton.builder().text("🏠 返回主页").callbackData("menu_start").build());
        rows.add(r);

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(rows).build();
        if (messageId != null) {
            updateMessage(chatId, messageId, sb.toString(), markup);
        } else {
            sendHtmlTextWithMarkup(chatId, sb.toString(), markup);
        }
    }

    public void answerCallback(String callbackQueryId, String text, boolean showAlert) {
        if (telegramClient == null || callbackQueryId == null) return;
        try {
            AnswerCallbackQuery query = AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .text(text)
                    .showAlert(showAlert)
                    .build();
            telegramClient.execute(query);
        } catch (Exception ignored) {}
    }

    public void updateMessage(long chatId, Integer messageId, String text, InlineKeyboardMarkup markup) {
        if (telegramClient == null || messageId == null) return;
        try {
            EditMessageText edit = EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(markup)
                    .build();
            telegramClient.execute(edit);
        } catch (Exception ignored) {}
    }

    public void sendText(long chatId, String text) {
        if (telegramClient == null || text == null || text.isBlank()) return;
        try {
            SendMessage msg = SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("Markdown")
                    .build();
            telegramClient.execute(msg);
        } catch (Exception e) {
            // Markdown 解析失败兜底用纯文本
            try {
                SendMessage fallback = SendMessage.builder()
                        .chatId(chatId)
                        .text(text)
                        .build();
                telegramClient.execute(fallback);
            } catch (Exception ignored) {}
        }
    }

    public void sendHtmlText(long chatId, String text) {
        if (telegramClient == null || text == null || text.isBlank()) return;
        try {
            SendMessage msg = SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("HTML")
                    .build();
            telegramClient.execute(msg);
        } catch (Exception ignored) {}
    }

    public void sendHtmlTextWithMarkup(long chatId, String text, InlineKeyboardMarkup markup) {
        if (telegramClient == null || text == null || text.isBlank()) return;
        try {
            SendMessage msg = SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(markup)
                    .build();
            telegramClient.execute(msg);
        } catch (Exception ignored) {}
    }

    public void sendHtmlTextWithButton(long chatId, String text, String buttonText, String callbackData) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow r = new InlineKeyboardRow();
        r.add(InlineKeyboardButton.builder().text(buttonText).callbackData(callbackData).build());
        rows.add(r);
        sendHtmlTextWithMarkup(chatId, text, InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    public void sendHtmlTextWithUrlButton(long chatId, String text, String buttonText, String url) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        InlineKeyboardRow r = new InlineKeyboardRow();
        r.add(InlineKeyboardButton.builder().text(buttonText).url(url).build());
        rows.add(r);
        sendHtmlTextWithMarkup(chatId, text, InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    public void sendMessage(String text) {
        if (adminId != null && !adminId.isBlank()) {
            try {
                sendText(Long.parseLong(adminId.trim()), text);
            } catch (Exception ignored) {}
        }
    }

    public void sendHtmlMessage(String text) {
        if (adminId != null && !adminId.isBlank()) {
            try {
                sendHtmlText(Long.parseLong(adminId.trim()), text);
            } catch (Exception ignored) {}
        }
    }

    public synchronized int getAndIncrementTotalSystemChanges() {
        int stored = 0;
        try {
            var opt = systemConfigRepo.findById(TOTAL_SYSTEM_IP_CHANGES_KEY);
            if (opt.isPresent() && opt.get().getConfigValue() != null) {
                stored = Integer.parseInt(opt.get().getConfigValue().trim());
            }
        } catch (Exception ignored) {}

        int newTotal = stored + 1;
        try {
            systemConfigRepo.saveAndFlush(new SystemConfig(TOTAL_SYSTEM_IP_CHANGES_KEY, String.valueOf(newTotal)));
        } catch (Exception ignored) {}
        return newTotal;
    }

    public int getTotalSystemChanges() {
        try {
            var opt = systemConfigRepo.findById(TOTAL_SYSTEM_IP_CHANGES_KEY);
            if (opt.isPresent() && opt.get().getConfigValue() != null) {
                return Integer.parseInt(opt.get().getConfigValue().trim());
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String generateShortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
