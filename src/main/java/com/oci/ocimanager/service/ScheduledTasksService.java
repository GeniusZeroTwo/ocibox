package com.oci.ocimanager.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oci.ocimanager.config.OciProperties;
import com.oci.ocimanager.entity.TrafficLimit;
import com.oci.ocimanager.repository.TrafficLimitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import com.oci.ocimanager.entity.SystemConfig;
import com.oci.ocimanager.repository.SystemConfigRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.Locale;

@Service
public class ScheduledTasksService {

    public static final Map<String, Double> TRAFFIC_CACHE = new ConcurrentHashMap<>();
    public static final Map<String, String> UPDATE_TIME_CACHE = new ConcurrentHashMap<>();
    
    // 记录本月已经发送过警告的账号，格式：Account -> "yyyy-MM"
    public static final Map<String, String> WARNING_SENT_RECORD = new ConcurrentHashMap<>();

    // 记录因超流量熔断被系统自动关机的实例 OCID 集合（持久化落盘，确保月初/提额复活仅唤醒熔断关机节点，绝不误启动手动关机备用机）
    public static final Set<String> MELTDOWN_STOPPED_OCIDS = ConcurrentHashMap.newKeySet();

    @Autowired private OciCloudService ociCloudService;
    @Autowired private TrafficLimitRepository limitRepo;
    @Autowired private OciProperties ociProperties;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OciAccountBillingService billingService;
    
    // 🌟 注入 Redis 以无缝替换系统文件存储
    @Autowired private LocalCacheService redisTemplate;
    @Autowired private SystemConfigRepository systemConfigRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.oci.ocimanager.service.NotificationService notificationService;
    @Autowired private com.oci.ocimanager.service.SystemUpdateService systemUpdateService;

    private static ScheduledTasksService instance;

    public static ScheduledTasksService getInstance() {
        return instance;
    }

    @PostConstruct
    public void initCacheOnStartup() {
        instance = this;
        // 🌟 从 SQLite 恢复缓存与本月已发送的警告记录，防止服务重启后数据丢失
        loadSystemCache();
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
            redisTemplate.opsForValue().set("oci:cache:meltdownOcids", objectMapper.writeValueAsString(MELTDOWN_STOPPED_OCIDS));

            // 持久化到 SQLite 数据库 system_config 表，确保服务重启/升级数据零丢失
            systemConfigRepository.save(new SystemConfig("CACHE_TRAFFIC", objectMapper.writeValueAsString(TRAFFIC_CACHE)));
            systemConfigRepository.save(new SystemConfig("CACHE_UPDATE_TIME", objectMapper.writeValueAsString(UPDATE_TIME_CACHE)));
            systemConfigRepository.save(new SystemConfig("CACHE_INSTANCES", objectMapper.writeValueAsString(OciCloudService.INSTANCE_CACHE)));
            systemConfigRepository.save(new SystemConfig("CACHE_WARNING_SENT", objectMapper.writeValueAsString(WARNING_SENT_RECORD)));
            systemConfigRepository.save(new SystemConfig("CACHE_MELTDOWN_STOPPED_OCIDS", objectMapper.writeValueAsString(MELTDOWN_STOPPED_OCIDS)));

            if (billingService != null) {
                billingService.saveBillsToDatabase();
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

            systemConfigRepository.findById("CACHE_MELTDOWN_STOPPED_OCIDS").ifPresent(cfg -> {
                try {
                    List<String> ocids = objectMapper.readValue(cfg.getConfigValue(), new TypeReference<List<String>>(){});
                    if (ocids != null) {
                        MELTDOWN_STOPPED_OCIDS.addAll(ocids);
                    }
                } catch (Exception ignored) {}
            });

            if (billingService != null) {
                billingService.loadBillsFromDatabase();
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

    // 🌟 定时检测 OCIBox 核心系统版本更新，发现新版本自动推送运维通知并支持一键热更新
    @Scheduled(fixedDelay = 21600000, initialDelay = 30000)
    public void scheduledUpdateCheck() {
        try {
            if (systemUpdateService != null) {
                systemUpdateService.checkUpdate(false);
            }
        } catch (Exception e) {
            System.err.println("ℹ️ [ScheduledTasksService] 定时版本检查提示: " + e.getMessage());
        }
    }

    public static void recordManualInstancePowerAction(String ocid, String action) {
        if (ocid == null || ocid.isBlank()) return;
        // 用户手动执行了开机/关机/删除：从熔断关机集合移除，尊重用户手动意图
        if (MELTDOWN_STOPPED_OCIDS.remove(ocid) && instance != null) {
            instance.saveSystemCache();
        }
    }

    public synchronized void migrateAccountRecords(String oldKey, String newKey) {
        if (oldKey == null || newKey == null || oldKey.equals(newKey)) return;
        Double traffic = TRAFFIC_CACHE.remove(oldKey);
        if (traffic != null) TRAFFIC_CACHE.put(newKey, traffic);

        String upd = UPDATE_TIME_CACHE.remove(oldKey);
        if (upd != null) UPDATE_TIME_CACHE.put(newKey, upd);

        String warn = WARNING_SENT_RECORD.remove(oldKey);
        if (warn != null) WARNING_SENT_RECORD.put(newKey, warn);

        String melt = WARNING_SENT_RECORD.remove(oldKey + "_meltdown");
        if (melt != null) WARNING_SENT_RECORD.put(newKey + "_meltdown", melt);

        saveSystemCache();
    }

    public synchronized void purgeAccountRecords(String accountKey) {
        if (accountKey == null || accountKey.isBlank()) return;
        TRAFFIC_CACHE.remove(accountKey);
        UPDATE_TIME_CACHE.remove(accountKey);
        WARNING_SENT_RECORD.remove(accountKey);
        WARNING_SENT_RECORD.remove(accountKey + "_meltdown");
        saveSystemCache();
    }

    /**
     * 统一流量单调保护落库与熔断/复活评估入口（供定时巡检、手动强制刷新、导入预热共用）。
     */
    public synchronized double evaluateAndRecordTrafficUsage(String accName, double rawUsage, String currentMonth) {
        if (accName == null || rawUsage < 0) {
            return -1.0;
        }
        String effectiveMonth = (currentMonth != null && !currentMonth.isBlank())
                ? currentMonth
                : ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM"));

        double usage = rawUsage;
        Double cachedUsage = TRAFFIC_CACHE.get(accName);
        String cachedTime = UPDATE_TIME_CACHE.get(accName);
        boolean sameUtcMonth = (cachedTime != null && cachedTime.startsWith(effectiveMonth));

        // 🛡️ 同自然月(UTC)内累计出站流量严格单调不减保护：拦截任何 API 延迟、抖动或空读导致的数据回退！
        if (cachedUsage != null && cachedUsage > 0.0) {
            if (sameUtcMonth && usage < cachedUsage) {
                System.err.println("⚠️ [流量单调保护] 账号 [" + accName + "] 同月(" + effectiveMonth + ")上报流量 " + usage
                        + "GB 低于已记录峰值 " + cachedUsage + "GB，保留原峰值以防误解除熔断！");
                usage = cachedUsage;
            } else if (cachedTime == null && cachedUsage > 5.0 && usage < 1.0) {
                System.err.println("⚠️ [流量异常拦截] 账号 [" + accName + "] 上报流量陡降至 " + usage
                        + "GB (历史缓存: " + cachedUsage + "GB)，保留原缓存值！");
                usage = cachedUsage;
            }
        }

        TRAFFIC_CACHE.put(accName, usage);
        UPDATE_TIME_CACHE.put(accName, ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " UTC");

        evaluateTrafficMeltdownOrRevive(accName, usage, effectiveMonth);
        saveSystemCache();
        return usage;
    }

    /**
     * 基于账号当前已知有效流量评估 98% 预警、超额自动熔断关机与合法提额恢复（修改限额时亦可立即调用）。
     */
    public synchronized void evaluateTrafficMeltdownOrRevive(String accName, double usage, String currentMonth) {
        evaluateTrafficMeltdownOrRevive(accName, usage, currentMonth, false);
    }

    public synchronized void evaluateTrafficMeltdownOrRevive(String accName, double usage, String currentMonth, boolean explicitLimitUpdate) {
        if (accName == null || usage < 0) return;
        String effectiveMonth = (currentMonth != null && !currentMonth.isBlank())
                ? currentMonth
                : ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM"));

        checkAndSendTrafficWarning(accName, usage, effectiveMonth);

        TrafficLimit limit = limitRepo.findById(accName).orElse(null);
        Integer limitGbObj = (limit != null) ? limit.getLimitGb() : null;
        String meltdownKey = accName + "_meltdown";

        if (limitGbObj != null && limitGbObj > 0 && usage >= limitGbObj) {
            if (!effectiveMonth.equals(WARNING_SENT_RECORD.get(meltdownKey))) {
                notificationService.send("账号流量熔断告警", "🛑 **【最高警报】账号流量熔断**\n账号 `" + accName + "` 流量超标 (" + String.format(Locale.US, "%.2f", usage) + "GB / 限额 " + limitGbObj + "GB)，正在错峰执行账号断网保护！", "DANGER", "TRAFFIC");
                WARNING_SENT_RECORD.put(meltdownKey, effectiveMonth);
                saveSystemCache();
            }

            Map<String, Map<String, String>> instances = ociCloudService.updateInstancesCache();
            boolean changedMeltdownSet = false;
            for (Map.Entry<String, Map<String, String>> entry : instances.entrySet()) {
                String ocid = entry.getKey();
                Map<String, String> info = entry.getValue();

                if (accName.equals(info.get("account")) && "RUNNING".equalsIgnoreCase(info.get("state"))) {
                    boolean success = ociCloudService.instanceAction(ocid, "SOFTSTOP", accName);
                    if (!success) {
                        success = ociCloudService.instanceAction(ocid, "STOP", accName);
                    }
                    if (success) {
                        MELTDOWN_STOPPED_OCIDS.add(ocid);
                        changedMeltdownSet = true;
                        info.put("state", "STOPPING");
                        notificationService.send("流量熔断切断实例", "🔌 成功切断: " + info.get("name"), "WARNING", "TRAFFIC");
                    }

                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
            }
            if (changedMeltdownSet) {
                saveSystemCache();
            }
        } else if (WARNING_SENT_RECORD.containsKey(meltdownKey)) {
            String recordedMonth = WARNING_SENT_RECORD.get(meltdownKey);
            boolean isMonthRollover = (recordedMonth != null && !effectiveMonth.equals(recordedMonth));

            boolean isLimitRaisedLegitimately = false;
            if (limitGbObj == null || limitGbObj <= 0) {
                isLimitRaisedLegitimately = true;
            } else if ((explicitLimitUpdate ? usage >= 0.0 : usage >= 1.0) && usage < limitGbObj) {
                isLimitRaisedLegitimately = true;
            }

            if (isMonthRollover || isLimitRaisedLegitimately) {
                WARNING_SENT_RECORD.remove(meltdownKey);
                saveSystemCache();
                notificationService.send("流量恢复自动开机", "🟢 **【流量恢复自动开机】**\n账号 `" + accName + "` 流量已恢复在安全限额内 (" + String.format(Locale.US, "%.2f", usage) + "GB)，已自动解除熔断锁定，正在唤醒此前因熔断关机的节点...", "SUCCESS", "TRAFFIC");
                autoReviveAccountInstances(accName);
            } else {
                System.out.println("🛡️ [熔断锁定保护] 账号 [" + accName + "] 仍处于本月 (" + recordedMonth + ") 熔断保护期内，当前流量读取为 " + usage + "GB，未达到解除熔断标准，拒绝误开机！");
            }
        }
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 600000)
    public void monitorTraffic() {
        int count = 0;
        String currentMonth = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM"));

        for (String accName : ociProperties.getAccounts().keySet()) {
            if (count > 0) {
                int jitter = 2200 + java.util.concurrent.ThreadLocalRandom.current().nextInt(1800);
                try { Thread.sleep(jitter); } catch (InterruptedException ignored) {}
            }
            count++;

            try {
                double usage = ociCloudService.fetchTrafficInGb(accName);
                if (usage >= 0) {
                    evaluateAndRecordTrafficUsage(accName, usage, currentMonth);
                }
            } catch (Exception e) {
                System.err.println("❌ 监控账号 [" + accName + "] 流量异常: " + e.getMessage());
            }
        }
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
            String adminMsg = "⚠️ *[超流量严重预警]* ⚠️\n\n" +
                    "您的甲骨文账号：`" + account + "`\n" +
                    "本月出站流量已达到设定限额的 **98%**！\n" +
                    "当前已用: `" + String.format(Locale.US, "%.2f", usage) + " GB` / `" + limit + " GB`\n\n" +
                    "**该账号下的实例：**\n`" + affectedStr + "`\n\n" +
                    "请注意排查流量消耗，避免停机或扣费风险！";
            notificationService.send("超流量严重预警 (98%)", adminMsg, "WARNING", "TRAFFIC");
            WARNING_SENT_RECORD.put(account, currentMonth);
            // 立即保存到 Redis
            saveSystemCache();
            
            limitOpt.setLastWarningMonth(currentMonth);
            limitRepo.save(limitOpt);
        }
    }

    @Scheduled(cron = "0 30 0 * * ?", zone = "UTC")
    public void dailyTrafficPush() {
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
                notificationService.send("每日全量流量播报", msg.toString(), "INFO", "TRAFFIC");
            } catch (Exception e) {
                System.err.println("❌ 向管理员推送每日流量失败: " + e.getMessage());
            }
        }
        System.out.println("✅ [每日推送] 管理员流量播报任务已完成！");
    }

    // 🌟 服务启动时补漏检测：严格以 OCI 账单周期的 UTC 每月 1 号为准，杜绝东八区提前 8 小时跨月误解锁！
    private void checkAndAutoStartOnStartup() {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(12000L);
                LocalDate nowUtc = LocalDate.now(ZoneOffset.UTC);
                if (nowUtc.getDayOfMonth() == 1) {
                    String currentMonth = nowUtc.format(DateTimeFormatter.ofPattern("yyyy-MM"));
                    String todayUtcKey = nowUtc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                    if (!isMonthAutoStartCompleted(currentMonth)) {
                        System.out.println("🗓️ [启动月初自愈] 检测到今天是 UTC 1 号 (" + todayUtcKey + ") 且本月尚未完成月初重置，正在执行...");
                        executeMonthAutoStart(false, "系统启动月初补漏 (UTC)");
                    } else if (!isDayOneRevivalCompleted(todayUtcKey)) {
                        autoReviveAllEligibleStoppedInstances("系统启动自愈巡检 (UTC)");
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
     * 严格对齐甲骨文官方 UTC 账单周期（每月 1 日 00:00 UTC 重置流量），持久化幂等，且默认仅唤醒因熔断关机的机器。
     */
    public synchronized Map<String, Object> executeMonthAutoStart(boolean force, String triggerSource) {
        LocalDate nowUtc = LocalDate.now(ZoneOffset.UTC);
        boolean isFirstDayUtc = (nowUtc.getDayOfMonth() == 1);

        String currentMonth = nowUtc.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String todayUtcKey = nowUtc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String redisKey = "oci:month_autostart_done:" + currentMonth;

        if (!force && !isFirstDayUtc) {
            return Map.of("success", false, "message", "今天并非 UTC 1 号 (当前 UTC: " + nowUtc + ")，甲骨文免费流量按 UTC 自然月重置；如需强制开启请传入 force=true");
        }
        if (!force && isMonthAutoStartCompleted(currentMonth)) {
            return Map.of("success", true, "skipped", true, "message", "本月 (" + currentMonth + " UTC) 月初自动复活已完成，跳过重复执行");
        }

        notificationService.send("月初自动复活启动",
            "🗓️ **【月初自动复活】**\n触发来源: `" + triggerSource + "`\n新月开始 (UTC)，甲骨文免费流量已重置！正在重置流量计数并唤醒熔断关机节点...", "INFO", "INSTANCE");

        int successCount = 0;
        int failedCount = 0;
        int skippedManualStopped = 0;
        int skippedRentals = 0;
        List<String> startedNames = new ArrayList<>();
        List<String> failedNames = new ArrayList<>();

        try {
            Set<String> meltdownTargets = new HashSet<>(MELTDOWN_STOPPED_OCIDS);

            System.out.println("🔄 [月初重置] 正在清空上月流量与账单缓存并强制拉取新数据 (触发源: " + triggerSource + ")...");
            TRAFFIC_CACHE.clear();
            UPDATE_TIME_CACHE.clear();
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

            Set<String> expiredRentalOcids = getExpiredRentalOcids();
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
                        continue;
                    }
                    // 🔒 非强制模式下，严格只唤醒此前因超流量熔断关机的机器，绝不无差别启动用户手动关机的备用机！
                    if (!force && !meltdownTargets.contains(ocid)) {
                        skippedManualStopped++;
                        continue;
                    }

                    boolean ok = ociCloudService.instanceAction(ocid, "START", account);
                    if (!ok) {
                        try { Thread.sleep(3000L); } catch (InterruptedException ignored) {}
                        ok = ociCloudService.instanceAction(ocid, "START", account);
                    }

                    if (ok) {
                        successCount++;
                        MELTDOWN_STOPPED_OCIDS.remove(ocid);
                        startedNames.add(name);
                        String cleanName = cleanInstanceDisplayName(name, account);
                        notificationService.send("自动开机成功", "🚀 **【自动开机成功】** `" + cleanName + "` (" + account + ")", "SUCCESS", "INSTANCE");
                    } else {
                        failedCount++;
                        failedNames.add(name);
                        System.err.println("❌ [月初复活开机失败] 实例: " + name + " (" + ocid + ")");
                    }
                    try { Thread.sleep(5000L); } catch (InterruptedException ignored) {}
                } else if ("RUNNING".equalsIgnoreCase(state)) {
                    MELTDOWN_STOPPED_OCIDS.remove(ocid);
                }
            }

            markMonthAutoStartCompleted(currentMonth, redisKey);

            String resultMsg;
            if (successCount > 0) {
                resultMsg = "✅ **【月初复活完毕】** 共自动唤醒了 `" + successCount + "` 台熔断关机节点" +
                    (failedCount > 0 ? "，`" + failedCount + "` 台失败" : "") +
                    (skippedManualStopped > 0 ? "，保留 `" + skippedManualStopped + "` 台手动关机备用节点" : "") +
                    "。新的一月，10TB 额度已就绪！";
            } else {
                resultMsg = "ℹ️ **【月初重置完毕】** 已重置全账号月度流量计数，当前无需要唤醒的熔断关机节点" +
                    (skippedManualStopped > 0 ? "（保留 `" + skippedManualStopped + "` 台手动关机节点）" : "") +
                    "。";
            }
            notificationService.send("月初复活完毕", resultMsg, "SUCCESS", "INSTANCE");

            ociCloudService.updateInstancesCache(true);
            saveSystemCache();

            if (failedCount == 0 && isFirstDayUtc) {
                markDayOneRevivalCompleted(todayUtcKey);
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("startedCount", successCount);
            resp.put("failedCount", failedCount);
            resp.put("skippedManualStopped", skippedManualStopped);
            resp.put("skippedRentals", skippedRentals);
            resp.put("startedInstances", startedNames);
            resp.put("failedInstances", failedNames);
            resp.put("message", resultMsg.replaceAll("[*`]", ""));
            return resp;

        } catch (Exception e) {
            System.err.println("❌ 月初自动开机任务异常: " + e.getMessage());
            String errMsg = "❌ 月初自动开机任务异常，请到服务日志查看详情。";
            try {
                notificationService.send("月初自动开机任务异常", errMsg, "DANGER", "INSTANCE");
            } catch (Exception ignored) {}
            return Map.of("success", false, "error", errMsg);
        }
    }

    // 🌟 定时任务：严格仅在 UTC 每月 1 号 00:10 (北京时间 1 号 08:10) 执行，与 OCI 官方 UTC 流量重置时刻严格对齐！
    @Scheduled(cron = "0 10 0 1 * ?", zone = "UTC")
    public void autoStartAtMonthStart() {
        executeMonthAutoStart(false, "定时任务 (00:10 UTC)");
    }

    private static final Map<String, Long> LAST_AUTO_START_ATTEMPT = new ConcurrentHashMap<>();
    private static final Map<String, Integer> AUTO_START_FAIL_COUNTS = new ConcurrentHashMap<>();
    private static volatile String dayOneRevivalCompletedDate = null;
    private static volatile String monthAutoStartCompletedMonth = null;

    private boolean isMonthAutoStartCompleted(String monthKey) {
        if (monthKey == null) return false;
        if (monthKey.equals(monthAutoStartCompletedMonth)) {
            return true;
        }
        try {
            if ("done".equals(redisTemplate.opsForValue().get("oci:month_autostart_done:" + monthKey))) {
                monthAutoStartCompletedMonth = monthKey;
                return true;
            }
        } catch (Exception ignored) {}
        try {
            String dbVal = systemConfigRepository.findById("MONTH_AUTOSTART_DONE")
                    .map(SystemConfig::getConfigValue).orElse(null);
            if (monthKey.equals(dbVal)) {
                monthAutoStartCompletedMonth = monthKey;
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void markMonthAutoStartCompleted(String monthKey, String redisKey) {
        monthAutoStartCompletedMonth = monthKey;
        try {
            redisTemplate.opsForValue().set(redisKey, "done", 35, TimeUnit.DAYS);
        } catch (Exception ignored) {}
        try {
            systemConfigRepository.save(new SystemConfig("MONTH_AUTOSTART_DONE", monthKey));
        } catch (Exception ignored) {}
    }

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
        try {
            String dbVal = systemConfigRepository.findById("DAY_ONE_REVIVAL_COMPLETED")
                    .map(SystemConfig::getConfigValue).orElse(null);
            if (dateKey != null && dateKey.equals(dbVal)) {
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
        try {
            systemConfigRepository.save(new SystemConfig("DAY_ONE_REVIVAL_COMPLETED", dateKey));
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
     * 🤖 【自动恢复指定账号下因熔断被关机的实例】
     * 账号流量熔断解除后调用，仅恢复 MELTDOWN_STOPPED_OCIDS 中记录的实例。
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
                        if (expiredRentalOcids.contains(ocid) || !MELTDOWN_STOPPED_OCIDS.contains(ocid)) {
                            continue;
                        }
                        boolean ok = ociCloudService.instanceAction(ocid, "START", accName);
                        if (!ok) {
                            try { Thread.sleep(3000L); } catch (InterruptedException ignored) {}
                            ok = ociCloudService.instanceAction(ocid, "START", accName);
                        }
                        if (ok) {
                            revived++;
                            MELTDOWN_STOPPED_OCIDS.remove(ocid);
                            String cleanName = cleanInstanceDisplayName(info.get("name"), accName);
                            notificationService.send("自动开机恢复", "🚀 **【自动开机恢复】**\n已唤醒熔断节点: `" + cleanName + "` (" + accName + ")", "SUCCESS", "INSTANCE");
                        }
                        try { Thread.sleep(5000L); } catch (InterruptedException ignored) {}
                    } else if (accName.equals(info.get("account")) && "RUNNING".equalsIgnoreCase(info.get("state"))) {
                        MELTDOWN_STOPPED_OCIDS.remove(ocid);
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
     * 🤖 【UTC 1号智能休眠自愈巡检】
     * 严格按 UTC 每月 1 号运行，仅补救月初首批未能成功拉起的熔断关机实例，完成后持久化标记停止后续轮询。
     */
    @Scheduled(fixedDelay = 1200000, initialDelay = 30000)
    public void dayOneAutoRevivalWatcher() {
        LocalDate nowUtc = LocalDate.now(ZoneOffset.UTC);
        if (nowUtc.getDayOfMonth() == 1) {
            String todayUtcKey = nowUtc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            if (isDayOneRevivalCompleted(todayUtcKey)) {
                return;
            }
            autoReviveAllEligibleStoppedInstances("UTC 1号持续自愈巡检");
        }
    }

    public void autoReviveAllEligibleStoppedInstances(String source) {
        CompletableFuture.runAsync(() -> {
            try {
                LocalDate nowUtc = LocalDate.now(ZoneOffset.UTC);
                if (nowUtc.getDayOfMonth() != 1) {
                    return;
                }
                String todayKey = nowUtc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                if (isDayOneRevivalCompleted(todayKey)) {
                    return;
                }

                if (MELTDOWN_STOPPED_OCIDS.isEmpty()) {
                    markDayOneRevivalCompleted(todayKey);
                    return;
                }

                Set<String> expiredRentalOcids = getExpiredRentalOcids();
                Map<String, Map<String, String>> instances = ociCloudService.updateInstancesCache(false);
                if (instances == null || instances.isEmpty()) return;

                List<Map.Entry<String, Map<String, String>>> eligibleStoppedList = new ArrayList<>();

                for (Map.Entry<String, Map<String, String>> e : instances.entrySet()) {
                    String ocid = e.getKey();
                    if (!MELTDOWN_STOPPED_OCIDS.contains(ocid)) {
                        continue;
                    }
                    Map<String, String> info = e.getValue();
                    if (expiredRentalOcids.contains(ocid)) {
                        continue;
                    }
                    String acc = info.get("account");
                    if (acc != null && WARNING_SENT_RECORD.containsKey(acc + "_meltdown")) {
                        continue;
                    }
                    String state = info.get("state");
                    if ("RUNNING".equalsIgnoreCase(state)) {
                        MELTDOWN_STOPPED_OCIDS.remove(ocid);
                    } else if ("STOPPED".equalsIgnoreCase(state)) {
                        eligibleStoppedList.add(e);
                    }
                }

                if (eligibleStoppedList.isEmpty()) {
                    saveSystemCache();
                    markDayOneRevivalCompleted(todayKey);
                    return;
                }

                List<Map.Entry<String, Map<String, String>>> toStartList = new ArrayList<>();
                for (Map.Entry<String, Map<String, String>> entry : eligibleStoppedList) {
                    String ocid = entry.getKey();
                    int fails = AUTO_START_FAIL_COUNTS.getOrDefault(ocid, 0);
                    if (fails >= 3) {
                        continue;
                    }
                    long lastAttempt = LAST_AUTO_START_ATTEMPT.getOrDefault(ocid, 0L);
                    if (System.currentTimeMillis() - lastAttempt > 600000L) {
                        toStartList.add(entry);
                    }
                }

                if (toStartList.isEmpty()) {
                    boolean allExhausted = eligibleStoppedList.stream()
                        .allMatch(e -> AUTO_START_FAIL_COUNTS.getOrDefault(e.getKey(), 0) >= 3);
                    if (allExhausted) {
                        markDayOneRevivalCompleted(todayKey);
                        notificationService.send("1号自愈巡检暂停",
                            "⚠️ **【1号自愈巡检暂停】**\n检测到有 `" + eligibleStoppedList.size() + "` 台熔断关机节点已连续重试 3 次均未能拉起。\n为保护账号与 API 安全，今日已自动停止轮询。", "WARNING", "INSTANCE");
                    }
                    return;
                }

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
                        MELTDOWN_STOPPED_OCIDS.remove(ocid);
                        AUTO_START_FAIL_COUNTS.remove(ocid);
                        String cleanName = cleanInstanceDisplayName(name, account);
                        notificationService.send("全自动开机自愈", "🤖 **【全自动开机自愈】**\n来源: `" + source + "`\n已成功唤醒熔断节点: `" + cleanName + "` (" + account + ")", "SUCCESS", "INSTANCE");
                    } else {
                        int f = AUTO_START_FAIL_COUNTS.getOrDefault(ocid, 0) + 1;
                        AUTO_START_FAIL_COUNTS.put(ocid, f);
                        System.err.println("❌ [" + source + "] 拉起实例 [" + name + "] 失败 (重试计数: " + f + "/3)");
                    }
                    try { Thread.sleep(5000L); } catch (InterruptedException ignored) {}
                }

                if (successCount > 0) {
                    try { Thread.sleep(8000L); } catch (InterruptedException ignored) {}
                    ociCloudService.updateInstancesCache(true);
                    saveSystemCache();

                    if (MELTDOWN_STOPPED_OCIDS.isEmpty()) {
                        markDayOneRevivalCompleted(todayKey);
                        notificationService.send("1号开机自愈达成",
                            "🎉 **【1号开机自愈达成】**\n全部熔断关机节点均已成功唤醒！今日自愈巡检任务已完成。", "SUCCESS", "INSTANCE");
                    }
                }
            } catch (Exception e) {
                System.err.println("全自动开机自愈巡检异常 (" + source + "): " + e.getMessage());
            }
        });
    }

    @Scheduled(cron = "0 0 3 * * ?", zone = "UTC")
    public void dailyDatabaseMaintenance() {
        System.out.println("🗄️ [数据库维护] 正在执行 SQLite WAL 日志合并与截断...");
        try {
            jdbcTemplate.execute("PRAGMA wal_checkpoint(TRUNCATE);");
            System.out.println("✅ [数据库维护] WAL 日志清理截断完成！");
        } catch (Exception e) {
            System.err.println("❌ [数据库维护] WAL 日志清理失败: " + e.getMessage());
        }
    }
}
