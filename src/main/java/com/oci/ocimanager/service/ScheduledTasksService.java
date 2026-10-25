package com.oci.ocimanager.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oci.ocimanager.config.OciProperties;
import com.oci.ocimanager.entity.TrafficLimit;
import com.oci.ocimanager.repository.TrafficLimitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.oci.ocimanager.entity.SystemConfig;
import com.oci.ocimanager.repository.SystemConfigRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Locale;

@Service
public class ScheduledTasksService {

    public static final Map<String, Double> TRAFFIC_CACHE = new ConcurrentHashMap<>();
    public static final Map<String, String> UPDATE_TIME_CACHE = new ConcurrentHashMap<>();
    
    // 记录本月已经发送过警告的账号，格式：Account -> "yyyy-MM"
    public static final Map<String, String> WARNING_SENT_RECORD = new ConcurrentHashMap<>();

    @Autowired private OciCloudService ociCloudService;
    @Autowired private TelegramBotService telegramBotService;
        @Autowired private TrafficLimitRepository limitRepo;
    @Autowired private GitHubService githubService;
    @Autowired private OciProperties ociProperties;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OciAccountBillingService billingService;
    
    // 🌟 注入 Redis 以无缝替换系统文件存储
    @Autowired private LocalCacheService redisTemplate;
    @Autowired private SystemConfigRepository systemConfigRepository;
    @Autowired private ObjectMapper objectMapper;

    @Value("${app.admin-id}") private String adminId;

    // 🔒 【硬编码密钥修复】备份加密口令改为配置注入，杜绝源码明文 "199852.."。
    @Value("${app.backup-password:}") private String backupPassword;

    private static ScheduledTasksService instance;

    public static ScheduledTasksService getInstance() {
        return instance;
    }

    @PostConstruct
    public void initCacheOnStartup() {
        instance = this;
        // 🌟 启动时执行兼容性数据迁移（从旧版 JSON 到 Redis）
        migrateOldJsonToRedis();
        // 从数据库恢复本月已发送的警告记录，防止代码更新后重复推送
        syncWarningRecordFromDb();
        // 🌟 启动补漏检查：如果今天就是 1 号且本月尚未执行自动开机复活，异步自动补救拉起
        checkAndAutoStartOnStartup();
        System.out.println("⚡ [快速启动] 已跳过启动时 OCI API 预热。需要最新状态时请在后台点击同步服务器状态。");
    }
    
        public void saveSystemCache() {
        try {
            redisTemplate.opsForValue().set("oci:cache:traffic", objectMapper.writeValueAsString(TRAFFIC_CACHE));
            redisTemplate.opsForValue().set("oci:cache:updateTime", objectMapper.writeValueAsString(UPDATE_TIME_CACHE));
            redisTemplate.opsForValue().set("oci:cache:instances", objectMapper.writeValueAsString(OciCloudService.INSTANCE_CACHE));
            redisTemplate.opsForValue().set("oci:cache:warningSent", objectMapper.writeValueAsString(WARNING_SENT_RECORD));

            // 持久化到 SQLite 数据库 system_config 表，确保服务重启/升级数据零丢失
            systemConfigRepository.save(new SystemConfig("CACHE_TRAFFIC", objectMapper.writeValueAsString(TRAFFIC_CACHE), "流量缓存"));
            systemConfigRepository.save(new SystemConfig("CACHE_UPDATE_TIME", objectMapper.writeValueAsString(UPDATE_TIME_CACHE), "更新时间缓存"));
            systemConfigRepository.save(new SystemConfig("CACHE_INSTANCES", objectMapper.writeValueAsString(OciCloudService.INSTANCE_CACHE), "实例缓存"));
            systemConfigRepository.save(new SystemConfig("CACHE_WARNING_SENT", objectMapper.writeValueAsString(WARNING_SENT_RECORD), "预警发送记录"));

            if (billingService != null) {
                billingService.saveBillsToRedis();
            }
        } catch (Exception e) {
            System.err.println("❌ 保存系统缓存失败: " + e.getMessage());
        }
    }

    private void loadSystemCache() {
        try {
            // 优先从 SQLite 恢复
            systemConfigRepository.findById("CACHE_TRAFFIC").ifPresent(cfg -> {
                try {
                    Map<String, Double> map = objectMapper.readValue(cfg.getConfigValue(), new TypeReference<Map<String, Double>>(){});
                    TRAFFIC_CACHE.putAll(map);
                } catch (Exception ignored) {}
            });

            systemConfigRepository.findById("CACHE_UPDATE_TIME").ifPresent(cfg -> {
                try {
                    Map<String, String> map = objectMapper.readValue(cfg.getConfigValue(), new TypeReference<Map<String, String>>(){});
                    UPDATE_TIME_CACHE.putAll(map);
                } catch (Exception ignored) {}
            });

            systemConfigRepository.findById("CACHE_INSTANCES").ifPresent(cfg -> {
                try {
                    Map<String, Map<String, String>> loadedInstances = objectMapper.readValue(cfg.getConfigValue(), new TypeReference<Map<String, Map<String, String>>>(){});
                    Set<String> validAccounts = ociProperties.getAccounts().keySet();
                    loadedInstances.entrySet().removeIf(entry -> {
                        Map<String, String> info = entry.getValue();
                        String acc = info.get("account");
                        if (acc == null || !validAccounts.contains(acc)) return true;
                        String state = info.get("state");
                        return "TERMINATED".equalsIgnoreCase(state);
                    });
                    OciCloudService.INSTANCE_CACHE.putAll(loadedInstances);
                } catch (Exception ignored) {}
            });

            systemConfigRepository.findById("CACHE_WARNING_SENT").ifPresent(cfg -> {
                try {
                    Map<String, String> map = objectMapper.readValue(cfg.getConfigValue(), new TypeReference<Map<String, String>>(){});
                    WARNING_SENT_RECORD.putAll(map);
                } catch (Exception ignored) {}
            });

            if (billingService != null) {
                billingService.loadBillsFromRedis();
            }
            System.out.println("📦 [SQLite 缓存恢复] 成功恢复历史流量与实例监控数据！");
        } catch (Exception e) {
            System.err.println("❌ 从 SQLite 读取缓存失败: " + e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 43200000, initialDelay = 600000)
    public void keepCacheWarm() {
        System.out.println("🔄 [缓存保活] 正在后台静默更新服务器列表缓存...");
        ociCloudService.updateInstancesCache();
        saveSystemCache();
    }

    // 🌟 每小时在后台静默刷新所有账号的当月账单，彻底消除控制台打开时的"自动计算中..."
    @Scheduled(fixedDelay = 3600000, initialDelay = 180000)
    public void monitorBills() {
        if (billingService != null) {
            billingService.refreshAllBillsInBackground();
        }
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 600000)
    public void monitorTraffic() {
        boolean hasUpdate = false;
        int count = 0;
        String currentMonth = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM"));

        for (String accName : ociProperties.getAccounts().keySet()) {
            if (count > 0) {
                // 🌟 差异化时序间隔：各账号独立动态微抖动 (2200ms ~ 4000ms)，杜绝固定周期网络指纹
                int jitter = 2200 + java.util.concurrent.ThreadLocalRandom.current().nextInt(1800);
                try { Thread.sleep(jitter); } catch (InterruptedException ignored) {}
            }
            count++;

            try {
                double usage = ociCloudService.fetchTrafficInGb(accName);
                if (usage >= 0) {
                    // 🛡️ 流量单调性校验：在同一个自然月内，累计出站流量只增不减！
                    // 若此前已记录了较多流量(>5GB)，而本次获取陡降至 0GB 附近，判定为 OCI Telemetry 接口未就绪或空数据，绝不采信！
                    Double cachedUsage = TRAFFIC_CACHE.get(accName);
                    if (cachedUsage != null && cachedUsage > 5.0 && usage < 1.0) {
                        System.err.println("⚠️ [流量异常拦截] 账号 [" + accName + "] 上报流量异常归零/暴跌至 " + usage + "GB (上一缓存值: " + cachedUsage + "GB)，判定为 OCI API 瞬时抖动或未就绪，保留原缓存值并阻断误操作！");
                        usage = cachedUsage;
                    }

                    TRAFFIC_CACHE.put(accName, usage);
                    UPDATE_TIME_CACHE.put(accName, ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " UTC");
                    hasUpdate = true;

                    checkAndSendTrafficWarning(accName, usage, currentMonth);

                    TrafficLimit limit = limitRepo.findById(accName).orElse(null);
                    if (limit != null && limit.getLimitGb() > 0 && usage >= limit.getLimitGb()) {
                        String meltdownKey = accName + "_meltdown";
                        if (!currentMonth.equals(WARNING_SENT_RECORD.get(meltdownKey))) {
                            telegramBotService.sendText(Long.parseLong(adminId), "🛑 **【最高警报】账号流量熔断**\n账号 `" + accName + "` 流量超标 (" + String.format(Locale.US, "%.2f", usage) + "GB)，正在错峰执行账号断网保护！");
                            WARNING_SENT_RECORD.put(meltdownKey, currentMonth);
                            saveSystemCache();
                        }

                        Map<String, Map<String, String>> instances = ociCloudService.updateInstancesCache();
                        for (Map.Entry<String, Map<String, String>> entry : instances.entrySet()) {
                            String ocid = entry.getKey();
                            Map<String, String> info = entry.getValue();

                            if (accName.equals(info.get("account")) && "RUNNING".equalsIgnoreCase(info.get("state"))) {
                                boolean success = ociCloudService.instanceAction(ocid, "SOFTSTOP", accName);
                                if (!success) {
                                    success = ociCloudService.instanceAction(ocid, "STOP", accName);
                                }
                                if (success) telegramBotService.sendText(Long.parseLong(adminId), "🔌 成功切断: " + info.get("name"));

                                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                            }
                        }
                    } else {
                        // 🌟 自动复活恢复闭环：严格防误触校验
                        String meltdownKey = accName + "_meltdown";
                        if (WARNING_SENT_RECORD.containsKey(meltdownKey)) {
                            String recordedMonth = WARNING_SENT_RECORD.get(meltdownKey);
                            boolean isMonthRollover = (recordedMonth != null && !currentMonth.equals(recordedMonth));

                            // 🔒 同月内解除熔断的合法条件判定：
                            // 必须是管理员在后台真正提高或取消了限额（限额为0或null，或者真实有效流量 > 1.0GB 且明确小于新限额）
                            // 绝对禁止因为 usage 接近 0.00GB（API 故障/冷启动空数据）而误解除同月熔断！
                            boolean isLimitRaisedLegitimately = false;
                            if (limit == null || limit.getLimitGb() == null || limit.getLimitGb() <= 0) {
                                isLimitRaisedLegitimately = true;
                            } else if (usage > 1.0 && usage < limit.getLimitGb()) {
                                isLimitRaisedLegitimately = true;
                            }

                            if (isMonthRollover || isLimitRaisedLegitimately) {
                                WARNING_SENT_RECORD.remove(meltdownKey);
                                saveSystemCache();
                                telegramBotService.sendText(Long.parseLong(adminId), "🟢 **【流量恢复自动开机】**\n账号 `" + accName + "` 流量已恢复正常 (" + String.format(Locale.US, "%.2f", usage) + "GB)，已自动解除熔断锁定，正在自动拉起关机节点...");
                                autoReviveAccountInstances(accName);
                            } else {
                                System.out.println("🛡️ [熔断锁定保护] 账号 [" + accName + "] 仍处于本月 (" + recordedMonth + ") 熔断保护期内，当前流量读取为 " + usage + "GB，未达到解除熔断标准，拒绝误开机！");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ 监控账号 [" + accName + "] 流量异常: " + e.getMessage());
            }
        }
        if (hasUpdate) saveSystemCache();
    }

    public void checkAndSendTrafficWarning(String account, double usage, String currentMonth) {
        TrafficLimit limitOpt = limitRepo.findById(account).orElse(null);
        if (limitOpt == null || limitOpt.getLimitGb() == null || limitOpt.getLimitGb() <= 0) {
            return; 
        }

        double limit = limitOpt.getLimitGb();
        
        if (usage >= limit * 0.98) {
            String lastSent = WARNING_SENT_RECORD.get(account);
            if (currentMonth.equals(lastSent)) {
                return; 
            }

            if (currentMonth.equals(limitOpt.getLastWarningMonth())) {
                WARNING_SENT_RECORD.put(account, currentMonth); 
                saveSystemCache();
                return;
            }

            System.out.println("⚠️ 账号 [" + account + "] 流量达到 98%，准备向拥有该账号机器的用户发送警告...");

            Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
            Set<String> accountOcids = new HashSet<>();
            List<String> allAffectedMachines = new ArrayList<>(); 
            
            for (Map.Entry<String, Map<String, String>> entry : instances.entrySet()) {
                if (account.equals(entry.getValue().get("account"))) {
                    accountOcids.add(entry.getKey());
                    if (entry.getValue().containsKey("name")) {
                        allAffectedMachines.add(entry.getValue().get("name"));
                    }
                }
            }

            String affectedStr = allAffectedMachines.isEmpty() ? "未找到该账号的实例" : String.join("`\n`", allAffectedMachines);
            if (adminId != null && !adminId.isBlank()) {
                try {
                    String adminMsg = "⚠️ *[超流量严重预警]* ⚠️\n\n" +
                            "您的甲骨文账号：`" + account + "`\n" +
                            "本月出站流量已达到设定限额的 **98%**！\n" +
                            "当前已用: `" + String.format(Locale.US, "%.2f", usage) + " GB` / `" + limit + " GB`\n\n" +
                            "**该账号下的实例：**\n`" + affectedStr + "`\n\n" +
                            "请注意排查流量消耗，避免停机或扣费风险！";
                    telegramBotService.sendText(Long.parseLong(adminId), adminMsg);
                } catch (Exception ignored) {}
            }
            WARNING_SENT_RECORD.put(account, currentMonth);
            // 立即保存到 Redis
            saveSystemCache();
            
            limitOpt.setLastWarningMonth(currentMonth);
            limitRepo.save(limitOpt);
        }
    }

    @Scheduled(cron = "0 30 0 * * ?", zone = "UTC")
    public void dailyTrafficPush() {
        if (adminId == null || adminId.isBlank()) return;
        System.out.println("📢 [每日推送] 开始向管理员推送区域流量报告...");
        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();

        Map<String, List<String>> accountIps = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : instances.entrySet()) {
            String ocid = entry.getKey();
            Map<String, String> info = entry.getValue();
            if (info != null && info.containsKey("account")) {
                String accName = info.get("account");
                String ip = ociCloudService.getCachedIp(ocid);
                if (ip == null || ip.startsWith("待获取") || ip.contains("关机") || ip.contains("无私网IP")) {
                    ip = ociCloudService.getInstanceIp(ocid, accName);
                }
                if (ip == null || ip.isEmpty()) ip = "未分配IP";
                accountIps.computeIfAbsent(accName, k -> new ArrayList<>()).add(ip);
            }
        }

        if (!accountIps.isEmpty()) {
            StringBuilder msg = new StringBuilder("📊 **【每日全量流量使用播报】**\n");
            for (Map.Entry<String, List<String>> entry : accountIps.entrySet()) {
                String accName = entry.getKey();
                List<String> ips = entry.getValue();
                Double usage = TRAFFIC_CACHE.getOrDefault(accName, -1.0);
                TrafficLimit limit = limitRepo.findById(accName).orElse(null);
                int limitGb = (limit != null && limit.getLimitGb() != null) ? limit.getLimitGb() : 0;
                String region = ociProperties.getAccounts().containsKey(accName) ? ociProperties.getAccounts().get(accName).getRegion() : "未知区域";

                msg.append("\n☁️ 账号: ").append(accName).append(" (").append(region).append(")\n");
                msg.append("🌐 关联 IP: ").append(String.join(", ", ips)).append("\n");
                if (usage >= 0) {
                    if (limitGb > 0) {
                        double percent = (usage / limitGb) * 100;
                        String icon = percent >= 90 ? "🔴" : (percent >= 70 ? "🟡" : "🟢");
                        msg.append("📈 ").append(icon).append(" 用量: ").append(String.format(Locale.US, "%.2f", usage)).append(" GB / ").append(limitGb).append(" GB (").append(String.format(Locale.US, "%.1f", percent)).append("%)\n");
                    } else {
                        msg.append("📈 🟢 用量: ").append(String.format(Locale.US, "%.2f", usage)).append(" GB / 不限流量\n");
                    }
                } else {
                    msg.append("📈 ❓ 用量: API数据抓取中或未配置\n");
                }
            }
            try {
                telegramBotService.sendText(Long.parseLong(adminId), msg.toString());
            } catch (Exception e) {
                System.err.println("❌ 向管理员推送每日流量失败: " + e.getMessage());
            }
        }
        System.out.println("✅ [每日推送] 管理员流量播报任务已完成！");
    }

    // 🌟 服务启动时补漏检测：如果今天就是 1 号，异步自动检测并补开机所有关机节点
    private void checkAndAutoStartOnStartup() {
        CompletableFuture.runAsync(() -> {
            try {
                // 等待各组件与 Client 预热 12 秒
                Thread.sleep(12000L);
                LocalDate nowUtc = LocalDate.now(ZoneOffset.UTC);
                LocalDate nowCst = LocalDate.now(ZoneId.of("Asia/Shanghai"));
                if (nowUtc.getDayOfMonth() == 1 || nowCst.getDayOfMonth() == 1) {
                    String todayKey = nowCst.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                    if (!isDayOneRevivalCompleted(todayKey)) {
                        System.out.println("🗓️ [启动全自动巡检] 检测到今天是 1 号且尚未完成自愈，立即自动扫描并补开机所有关机节点...");
                        autoReviveAllEligibleStoppedInstances("系统启动自愈巡检");
                    }
                }
            } catch (Exception e) {
                System.err.println("启动检查月初开机异常: " + e.getMessage());
            }
        });
    }

    /**
     * 提前提取已到期且满 15 小时缓冲关机的租户机器 OCID，避免误将其作为月初免费流量复活
     */
    private Set<String> getExpiredRentalOcids() { return Collections.emptySet(); }

    /**
     * 🗓️ 【月初自动复活 / 批量开机核心逻辑】
     * 统一入口，支持定时任务、启动补漏、后台接口与 TG 指令调用
     */
    public synchronized Map<String, Object> executeMonthAutoStart(boolean force, String triggerSource) {
        LocalDate nowUtc = LocalDate.now(ZoneOffset.UTC);
        LocalDate nowCst = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        boolean isFirstDay = (nowUtc.getDayOfMonth() == 1 || nowCst.getDayOfMonth() == 1);

        String currentMonth = nowUtc.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String redisKey = "oci:month_autostart_done:" + currentMonth;

        if (!force && !isFirstDay) {
            return Map.of("success", false, "message", "今天并非 1 号 (UTC: " + nowUtc + ", CST: " + nowCst + ")，如需强制开启请传入 force=true");
        }

        telegramBotService.sendText(Long.parseLong(adminId), 
            "🗓️ **【月初自动复活】**\n触发来源: `" + triggerSource + "`\n新月开始，甲骨文免费流量已重置！正在扫描并错峰开启所有已关机节点...");

        int successCount = 0;
        int failedCount = 0;
        int skippedRentals = 0;
        List<String> startedNames = new ArrayList<>();
        List<String> failedNames = new ArrayList<>();

        try {
            System.out.println("🔄 [月初重置] 正在清空上月流量与账单缓存并强制拉取新数据 (触发源: " + triggerSource + ")...");
            TRAFFIC_CACHE.clear();
            UPDATE_TIME_CACHE.clear();
            // 🌟 清除上月的流量熔断记录，解除停机锁定并清空重试冷却
            WARNING_SENT_RECORD.entrySet().removeIf(e -> e.getKey().endsWith("_meltdown"));
            LAST_AUTO_START_ATTEMPT.clear();
            AUTO_START_FAIL_COUNTS.clear();

            if (billingService != null) {
                try {
                    billingService.handleMonthRollover();
                } catch (Exception be) {
                    System.err.println("⚠️ [月初重置] 账单月度重置异常: " + be.getMessage());
                }
            }

            // 独立容错拉取各账号最新流量，单个账号异常不阻塞其他账号与开机流程
            for (String accName : ociProperties.getAccounts().keySet()) {
                try {
                    double usage = ociCloudService.fetchTrafficInGb(accName);
                    if (usage >= 0) {
                        TRAFFIC_CACHE.put(accName, usage);
                        UPDATE_TIME_CACHE.put(accName, ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " UTC");
                    }
                } catch (Exception ex) {
                    System.err.println("⚠️ [月初复活] 拉取账号 [" + accName + "] 流量异常: " + ex.getMessage());
                }
            }

            // 提前获取超期满15小时已执行关机保护的租户实例 OCID 集合
            Set<String> expiredRentalOcids = getExpiredRentalOcids();

            // 强制从甲骨文云端拉取全量机器最新真实状态
            Map<String, Map<String, String>> instances = ociCloudService.updateInstancesCache(true);

            for (Map.Entry<String, Map<String, String>> entry : instances.entrySet()) {
                String ocid = entry.getKey();
                Map<String, String> info = entry.getValue();
                String state = info.get("state");
                String name = info.get("name") != null ? info.get("name") : ocid;
                String account = info.get("account");

                if ("STOPPED".equalsIgnoreCase(state)) {
                    if (expiredRentalOcids.contains(ocid)) {
                        skippedRentals++;
                        System.out.println("ℹ️ [月初复活跳过] 实例 [" + name + "] 属于到期未续费租户，跳过自动开机");
                        continue;
                    }

                    boolean ok = ociCloudService.instanceAction(ocid, "START", account);
                    if (!ok) {
                        // 失败间隔 3 秒重试一次，提升对抗 Oracle API 抖动能力
                        try { Thread.sleep(3000L); } catch (InterruptedException ignored) {}
                        ok = ociCloudService.instanceAction(ocid, "START", account);
                    }

                    if (ok) {
                        successCount++;
                        startedNames.add(name);
                        String cleanName = cleanInstanceDisplayName(name, account);
                        telegramBotService.sendText(Long.parseLong(adminId), "🚀 **【自动开机成功】** `" + cleanName + "` (" + account + ")");
                    } else {
                        failedCount++;
                        failedNames.add(name);
                        System.err.println("❌ [月初复活开机失败] 实例: " + name + " (" + ocid + ")");
                    }
                    try { Thread.sleep(5000L); } catch (InterruptedException ignored) {}
                }
            }

            // 标记本月已完成
            try {
                redisTemplate.opsForValue().set(redisKey, "done", 32, TimeUnit.DAYS);
            } catch (Exception ignored) {}

            String resultMsg;
            if (successCount > 0) {
                resultMsg = "✅ **【月初复活完毕】** 共自动唤醒了 `" + successCount + "` 台机器" + 
                    (failedCount > 0 ? "，`" + failedCount + "` 台失败" : "") + 
                    (skippedRentals > 0 ? "，跳过 `" + skippedRentals + "` 台超期租户机器" : "") + 
                    "。新的一月，10TB 额度已就绪！";
            } else {
                resultMsg = "ℹ️ **【月初复活完毕】** 未检测到需要开机的关机节点 (已启动: 0" + 
                    (skippedRentals > 0 ? "，跳过超期租户: " + skippedRentals : "") + 
                    ")，所有健康节点均在运行中。";
            }
            telegramBotService.sendText(Long.parseLong(adminId), resultMsg);

            // 更新缓存并持久化
            ociCloudService.updateInstancesCache(true);
            saveSystemCache();

            // 🌟 若所有需要开机的机器均已开机（失败数为0），立即标记今日自愈完成，巡检器彻底停止后续轮询！
            if (failedCount == 0) {
                String todayKey = nowCst.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                markDayOneRevivalCompleted(todayKey);
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("startedCount", successCount);
            resp.put("failedCount", failedCount);
            resp.put("skippedRentals", skippedRentals);
            resp.put("startedInstances", startedNames);
            resp.put("failedInstances", failedNames);
            resp.put("message", resultMsg.replaceAll("[*`]", ""));
            return resp;

        } catch (Exception e) {
            String errMsg = "❌ 月初自动开机任务异常: " + e.getMessage();
            telegramBotService.sendText(Long.parseLong(adminId), errMsg);
            return Map.of("success", false, "error", errMsg);
        }
    }

    // 🌟 定时任务 1：东八区 (Asia/Shanghai) 每月 1 号 00:10 执行
    @Scheduled(cron = "0 10 0 1 * ?", zone = "Asia/Shanghai")
    public void autoStartAtChinaMonthStart() {
        executeMonthAutoStart(false, "定时任务 (00:10 北京时间)");
    }

    // 🌟 定时任务 2：UTC 每月 1 号 00:10 (北京时间 08:10) 执行
    @Scheduled(cron = "0 10 0 1 * ?", zone = "UTC")
    public void autoStartAtMonthStart() {
        executeMonthAutoStart(false, "定时任务 (00:10 UTC)");
    }

    // 记录各实例最近一次自动开机尝试的时间戳，防 Oracle API 报错时持续频繁重试
    private static final Map<String, Long> LAST_AUTO_START_ATTEMPT = new ConcurrentHashMap<>();

    // 记录各实例在当日自动开机尝试失败的次数，达到 3 次则熔断保护，防止云端严重缺机时死循环敲接口
    private static final Map<String, Integer> AUTO_START_FAIL_COUNTS = new ConcurrentHashMap<>();

    // 🌟 1号全量自愈完成标志（日期如 "2026-10-01"）：一旦检测到所有客户/业务机器均已全部开机，立即休眠关闭今日后续巡检！
    private static volatile String dayOneRevivalCompletedDate = null;

    private boolean isDayOneRevivalCompleted(String dateKey) {
        if (dateKey != null && dateKey.equals(dayOneRevivalCompletedDate)) {
            return true;
        }
        try {
            String redisVal = redisTemplate.opsForValue().get("oci:day_one_completed:" + dateKey);
            if ("done".equals(redisVal)) {
                dayOneRevivalCompletedDate = dateKey;
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void markDayOneRevivalCompleted(String dateKey) {
        dayOneRevivalCompletedDate = dateKey;
        try {
            redisTemplate.opsForValue().set("oci:day_one_completed:" + dateKey, "done", 3, TimeUnit.DAYS);
        } catch (Exception ignored) {}
    }

    private String cleanInstanceDisplayName(String rawName, String account) {
        if (rawName == null || rawName.isBlank()) return "未知实例";
        String clean = rawName.trim();
        if (account != null && !account.isBlank()) {
            if (clean.startsWith("[" + account + "] ")) {
                clean = clean.substring(("[" + account + "] ").length());
            } else if (clean.startsWith(account + " ")) {
                clean = clean.substring((account + " ").length());
            } else if (clean.startsWith(account + "-")) {
                clean = clean.substring((account + "-").length());
            }
        }
        return clean.trim();
    }

    /**
     * 🤖 【全自动恢复指定账号下所有关机实例】
     * 账号流量熔断解除后，自动调用
     */
    public void autoReviveAccountInstances(String accName) {
        CompletableFuture.runAsync(() -> {
            try {
                if (accName != null && WARNING_SENT_RECORD.containsKey(accName + "_meltdown")) {
                    System.err.println("🛡️ [自动开机拦截] 账号 [" + accName + "] 仍处于流量熔断保护期内，拒绝执行开机！");
                    return;
                }
                Set<String> expiredRentalOcids = getExpiredRentalOcids();
                Map<String, Map<String, String>> instances = ociCloudService.updateInstancesCache(true);
                int revived = 0;
                for (Map.Entry<String, Map<String, String>> entry : instances.entrySet()) {
                    String ocid = entry.getKey();
                    Map<String, String> info = entry.getValue();
                    if (accName.equals(info.get("account")) && "STOPPED".equalsIgnoreCase(info.get("state"))) {
                        if (expiredRentalOcids.contains(ocid)) {
                            continue;
                        }
                        boolean ok = ociCloudService.instanceAction(ocid, "START", accName);
                        if (!ok) {
                            try { Thread.sleep(3000L); } catch (InterruptedException ignored) {}
                            ok = ociCloudService.instanceAction(ocid, "START", accName);
                        }
                        if (ok) {
                            revived++;
                            String cleanName = cleanInstanceDisplayName(info.get("name"), accName);
                            telegramBotService.sendText(Long.parseLong(adminId), "🚀 **【自动开机恢复】**\n已唤醒节点: `" + cleanName + "` (" + accName + ")");
                        }
                        try { Thread.sleep(5000L); } catch (InterruptedException ignored) {}
                    }
                }
                if (revived > 0) {
                    ociCloudService.updateInstancesCache(true);
                    saveSystemCache();
                }
            } catch (Exception e) {
                System.err.println("自动恢复账号实例异常 [" + accName + "]: " + e.getMessage());
            }
        });
    }

    /**
     * 🤖 【1号智能休眠自愈巡检】
     * 每 20 分钟巡检一次：
     * 只要今天处于 1 号，且检测到尚未完成今日自愈：
     * 自动扫描系统内是否存在处于 STOPPED 关机状态的可用机器（非超期租户）；
     * 🌟 一旦检测到所有客户/业务机器均已全部处于开机运行状态，立即打上今日完成标记并停止检查！
     * 全天后续时间直接 0ms 闪退，绝不浪费任何 Oracle API 请求或打扰客户。
     */
    @Scheduled(fixedDelay = 1200000, initialDelay = 30000)
    public void dayOneAutoRevivalWatcher() {
        LocalDate nowUtc = LocalDate.now(ZoneOffset.UTC);
        LocalDate nowCst = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if (nowUtc.getDayOfMonth() == 1 || nowCst.getDayOfMonth() == 1) {
            String todayKey = nowCst.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            if (isDayOneRevivalCompleted(todayKey)) {
                // 🌟 所有客户机器均已开机确认，直接退出，今日绝不重复检查
                return;
            }
            autoReviveAllEligibleStoppedInstances("1号持续自愈巡检");
        }
    }

    /**
     * 🤖 【全自动保活自愈：扫描并启动所有合规关机节点】
     * 只要发现有 STOPPED 节点（且非超期租户）：
     * 自动下发 START 指令并重试，零人工介入！
     * 一旦检测到所有合规节点均已开机，自动休眠关闭今日后续巡检！
     */
    public void autoReviveAllEligibleStoppedInstances(String source) {
        CompletableFuture.runAsync(() -> {
            try {
                LocalDate nowCst = LocalDate.now(ZoneId.of("Asia/Shanghai"));
                String todayKey = nowCst.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                if (isDayOneRevivalCompleted(todayKey)) {
                    return;
                }

                Set<String> expiredRentalOcids = getExpiredRentalOcids();
                Map<String, Map<String, String>> instances = ociCloudService.updateInstancesCache(false);
                if (instances == null || instances.isEmpty()) return;

                List<Map.Entry<String, Map<String, String>>> eligibleStoppedList = new ArrayList<>();
                int totalEligible = 0;
                int runningCount = 0;

                for (Map.Entry<String, Map<String, String>> e : instances.entrySet()) {
                    String ocid = e.getKey();
                    Map<String, String> info = e.getValue();
                    if (expiredRentalOcids.contains(ocid)) {
                        continue; // 跳过超期欠费租户机器
                    }
                    String acc = info.get("account");
                    if (acc != null && WARNING_SENT_RECORD.containsKey(acc + "_meltdown")) {
                        continue; // 🛡️ 跳过仍处于流量熔断保护期的账号机器，防止任何自愈巡检误唤醒
                    }
                    totalEligible++;
                    String state = info.get("state");
                    if ("RUNNING".equalsIgnoreCase(state)) {
                        runningCount++;
                    } else if ("STOPPED".equalsIgnoreCase(state)) {
                        eligibleStoppedList.add(e);
                    }
                }

                // 🌟 核心优化：检测到客户机器全部已经开机，直接打上完成标记，退出休眠，今日彻底不再检查！
                if (eligibleStoppedList.isEmpty()) {
                    System.out.println("🎉 [1号自愈巡检] 确认所有客户与业务节点均已正常运行 (" + runningCount + "/" + totalEligible + ")！今日自愈任务达成，自动休眠停止后续检查。");
                    markDayOneRevivalCompleted(todayKey);
                    telegramBotService.sendText(Long.parseLong(adminId), 
                        "🎉 **【1号开机自愈达成】**\n已检测确认所有客户及业务机器全部处于正常运行状态 (运行中: `" + runningCount + "` 台)！\n今日自愈巡检已功成身退，已自动停止后续检查。");
                    return;
                }

                // 过滤出真正需要开机的节点（剔除失败达到 3 次的节点，避免云端缺机或严重异常时无休止死循环）
                List<Map.Entry<String, Map<String, String>>> toStartList = new ArrayList<>();
                for (Map.Entry<String, Map<String, String>> entry : eligibleStoppedList) {
                    String ocid = entry.getKey();
                    int fails = AUTO_START_FAIL_COUNTS.getOrDefault(ocid, 0);
                    if (fails >= 3) {
                        continue;
                    }
                    long lastAttempt = LAST_AUTO_START_ATTEMPT.getOrDefault(ocid, 0L);
                    if (System.currentTimeMillis() - lastAttempt > 600000L) { // 10分钟防刷冷却
                        toStartList.add(entry);
                    }
                }

                if (toStartList.isEmpty()) {
                    boolean allExhausted = eligibleStoppedList.stream()
                        .allMatch(e -> AUTO_START_FAIL_COUNTS.getOrDefault(e.getKey(), 0) >= 3);
                    if (allExhausted) {
                        System.out.println("⚠️ [1号自愈巡检] 剩余 " + eligibleStoppedList.size() + " 台关机节点均已达 3 次重试上限，自动停止今日检查，避免频繁调用。");
                        markDayOneRevivalCompleted(todayKey);
                        telegramBotService.sendText(Long.parseLong(adminId), 
                            "⚠️ **【1号自愈巡检暂停】**\n检测到有 `" + eligibleStoppedList.size() + "` 台关机机器已连续重试 3 次均未能拉起 (通常为甲骨文官方区域缺货 Out of Host Capacity 或已被封禁)。\n为保护账号与 API 安全，今日已自动停止轮询。");
                    }
                    return;
                }

                System.out.println("🤖 [" + source + "] 检测到 " + toStartList.size() + " 台关机节点需要拉起，正在全自动开机自愈...");
                int successCount = 0;
                for (Map.Entry<String, Map<String, String>> entry : toStartList) {
                    String ocid = entry.getKey();
                    Map<String, String> info = entry.getValue();
                    String name = info.get("name") != null ? info.get("name") : ocid;
                    String account = info.get("account");

                    LAST_AUTO_START_ATTEMPT.put(ocid, System.currentTimeMillis());
                    boolean ok = ociCloudService.instanceAction(ocid, "START", account);
                    if (!ok) {
                        try { Thread.sleep(3000L); } catch (InterruptedException ignored) {}
                        ok = ociCloudService.instanceAction(ocid, "START", account);
                    }

                    if (ok) {
                        successCount++;
                        AUTO_START_FAIL_COUNTS.remove(ocid);
                        String cleanName = cleanInstanceDisplayName(name, account);
                        telegramBotService.sendText(Long.parseLong(adminId), "🤖 **【全自动开机自愈】**\n来源: `" + source + "`\n已成功唤醒关机节点: `" + cleanName + "` (" + account + ")");
                    } else {
                        int f = AUTO_START_FAIL_COUNTS.getOrDefault(ocid, 0) + 1;
                        AUTO_START_FAIL_COUNTS.put(ocid, f);
                        System.err.println("❌ [" + source + "] 拉起实例 [" + name + "] 失败 (重试计数: " + f + "/3)");
                    }
                    try { Thread.sleep(5000L); } catch (InterruptedException ignored) {}
                }

                if (successCount > 0) {
                    try { Thread.sleep(8000L); } catch (InterruptedException ignored) {}
                    Map<String, Map<String, String>> updated = ociCloudService.updateInstancesCache(true);
                    saveSystemCache();

                    // 检查是否已经全量开机
                    boolean stillHasStopped = false;
                    for (Map.Entry<String, Map<String, String>> e : updated.entrySet()) {
                        String ocid = e.getKey();
                        if (!expiredRentalOcids.contains(ocid) && "STOPPED".equalsIgnoreCase(e.getValue().get("state"))) {
                            if (AUTO_START_FAIL_COUNTS.getOrDefault(ocid, 0) < 3) {
                                stillHasStopped = true;
                                break;
                            }
                        }
                    }

                    if (!stillHasStopped) {
                        // 全部合规节点已开机完毕！立即标记完成，终止今日后续所有检查！
                        markDayOneRevivalCompleted(todayKey);
                        telegramBotService.sendText(Long.parseLong(adminId), 
                            "🎉 **【1号开机自愈达成】**\n全部关机节点均已成功唤醒！今日自愈巡检任务已完成，已停止后续检查。");
                    }
                }
            } catch (Exception e) {
                System.err.println("全自动开机自愈巡检异常 (" + source + "): " + e.getMessage());
            }
        });
    }

    @Scheduled(cron = "0 0 3 * * ?", zone = "UTC")
    public void dailyBackup() {
        System.out.println("🗄️ [数据库维护] 正在执行 SQLite WAL 日志合并与截断...");
        try {
            jdbcTemplate.execute("PRAGMA wal_checkpoint(TRUNCATE);");
            System.out.println("✅ [数据库维护] WAL 日志清理完成，准备执行代码备份！");
        } catch (Exception e) {
            System.err.println("❌ [数据库维护] WAL 日志清理失败: " + e.getMessage());
        }

        List<String> filesToBackup = Arrays.asList(
            "/root/oci-personal/data",
            "/root/oci-personal/application.yml",
            "/root/oci-personal/keys"
        );

        if (backupPassword == null || backupPassword.isBlank()) {
            System.err.println("❌ [自动备份] 未配置备份加密口令，已跳过备份以保护密钥。");
            return;
        }
        try {
            githubService.backupFilesWithPassword(filesToBackup, "oci_personal_backup.zip", backupPassword);
            System.out.println("✅ [自动备份] 数据已安全打包并推送到 GitHub 仓库！");
        } catch (Exception e) {
            System.err.println("❌ [自动备份] 执行备份时发生异常: " + e.getMessage());
        }
    }
}
