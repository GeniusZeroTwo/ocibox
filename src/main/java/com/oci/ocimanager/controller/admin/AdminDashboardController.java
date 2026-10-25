package com.oci.ocimanager.controller.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oci.ocimanager.config.OciProperties;
import com.oci.ocimanager.entity.TrafficLimit;
import com.oci.ocimanager.repository.TrafficLimitRepository;
import com.oci.ocimanager.service.AccountYamlService;
import com.oci.ocimanager.service.AdminAuthService;
import com.oci.ocimanager.service.OciCloudService;
import com.oci.ocimanager.service.ScheduledTasksService;
import com.oci.ocimanager.service.TelegramBotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.annotation.PreDestroy;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * 后台总览数据接口：面板数据聚合(/data)、实例同步(/sync)、账号详情刷新(/sync-accounts)、流量查询(/traffic)。
 * 原属 AdminController，URL 保持不变。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminDashboardController {

    @Autowired private TrafficLimitRepository trafficLimitRepository;
    @Autowired private TelegramBotService telegramBotService;
    @Autowired private OciCloudService ociCloudService;
    @Autowired private OciProperties ociProperties;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AccountYamlService accountYamlService;
    @Autowired private AdminAuthService adminAuthService;

    private final ExecutorService ipFetchExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    public void destroy() {
        ipFetchExecutor.shutdownNow();
    }

    private boolean checkAuth(String token) {
        return adminAuthService.checkAuth(token);
    }

    private Map<String, Map<String, String>> getEnrichedInstances() {
        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        if (instances.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, Map<String, String>> enriched = new ConcurrentHashMap<>();
        List<String> needFetchOcids = new ArrayList<>();

        for (Map.Entry<String, Map<String, String>> entry : instances.entrySet()) {
            String ocid = entry.getKey();
            Map<String, String> info = new HashMap<>(entry.getValue());
            String cachedIp = ociCloudService.getCachedIp(ocid);
            String cachedIpv6 = ociCloudService.getCachedIpv6(ocid);
            if (cachedIpv6 != null && !cachedIpv6.isBlank()) {
                info.put("ipv6", cachedIpv6);
            }

            if (cachedIp != null && !cachedIp.startsWith("待获取")) {
                if (cachedIp.contains("无私网IP") || cachedIp.contains("关机")) {
                    cachedIp = "未分配 IP";
                }
                info.put("ip", cachedIp);
            } else if ("RUNNING".equals(info.get("state"))) {
                info.put("ip", "获取中...");
                needFetchOcids.add(ocid);
            } else {
                info.put("ip", "未分配 IP");
            }
            enriched.put(ocid, info);
        }

        if (!needFetchOcids.isEmpty()) {
            List<CompletableFuture<Void>> ipFutures = new ArrayList<>();
            for (String ocid : needFetchOcids) {
                Map<String, String> info = enriched.get(ocid);
                String account = info.get("account");
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        String ip = ociCloudService.getInstanceIp(ocid, account);
                        if (ip == null || ip.contains("无私网IP") || ip.contains("关机")) {
                            ip = "未分配 IP";
                        }
                        info.put("ip", ip);
                        String ipv6 = ociCloudService.getInstanceIpv6(ocid, account);
                        if (ipv6 != null && !ipv6.isBlank()) {
                            info.put("ipv6", ipv6);
                        }
                    } catch (Exception e) {
                        info.put("ip", "获取失败");
                    }
                }, ipFetchExecutor);
                ipFutures.add(future);
            }
            try {
                CompletableFuture.allOf(ipFutures.toArray(new CompletableFuture<?>[0]))
                        .get(60, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                for (String ocid : needFetchOcids) {
                    Map<String, String> info = enriched.get(ocid);
                    if ("获取中...".equals(info.get("ip"))) {
                        info.put("ip", "获取超时，请刷新");
                    }
                }
            } catch (Exception e) {
                System.err.println("并行获取IP异常: " + e.getMessage());
            }
        }

        List<CompletableFuture<Void>> locFutures = new ArrayList<>();
        for (Map<String, String> info : enriched.values()) {
            String ip = info.get("ip");
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                info.put("location", ociCloudService.getIpLocation(ip));
            }, ipFetchExecutor);
            locFutures.add(future);
        }
        try {
            CompletableFuture.allOf(locFutures.toArray(new CompletableFuture<?>[0]))
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            for (Map<String, String> info : enriched.values()) {
                if (!info.containsKey("location")) {
                    info.put("location", "未知地区");
                }
            }
        }

        for (Map<String, String> info : enriched.values()) {
            String ip = info.get("ip");
            if (ip != null && (ip.contains("(NAT)") || ip.contains("500M") || ip.contains("(原)"))) {
                info.put("is500m", "true");
            } else {
                info.put("is500m", "false");
            }
        }

        return enriched;
    }

    @PostMapping("/data")
    public Map<String, Object> getData(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "会话已过期，请重新登录");
        
        // 🌟 确保账号在内存和输出中均严格遵循 application.yml 物理添加顺序
        Map<String, OciProperties.Account> orderedAccounts = accountYamlService.reorderAccounts(ociProperties.getAccounts());
        ociProperties.setAccounts(orderedAccounts);
        List<Map<String, Object>> accountList = buildAccountDetailsList(orderedAccounts);
        
        return Map.of(
            "success", true, 
            "instances", getEnrichedInstances(), 
            "accounts", orderedAccounts.keySet(),
            "accountDetails", accountList,
            "tgBotRunning", telegramBotService.isBotRunning()
        );
    }

    private boolean isValidRegisterTime(String time) {
        return ociCloudService.isValidRegisterTime(time);
    }

    public void triggerAutoFetchRegisterTime(String accountName) {
        ociCloudService.triggerAutoFetchRegisterTime(accountName);
    }

    public List<Map<String, Object>> buildAccountDetailsList(Map<String, OciProperties.Account> orderedAccounts) {
        List<Map<String, Object>> accountList = new ArrayList<>();
        int orderIndex = 0;
        for (Map.Entry<String, OciProperties.Account> entry : orderedAccounts.entrySet()) {
            Map<String, Object> accInfo = new HashMap<>();
            String accountName = entry.getKey();
            OciProperties.Account acc = entry.getValue();
            
            accInfo.put("name", accountName);
            accInfo.put("region", acc.getRegion() != null ? acc.getRegion() : "未知");
            accInfo.put("subscribedRegions", String.join(",", ociCloudService.getSubscribedRegions(accountName)));
            accInfo.put("orderIndex", orderIndex++);
            
            String regTime = acc.getRegisterTime();
            
            // 🌟 仅当注册时间为空、未设置或明确处于探测中时，才触发自动抓取；若为"未记录"或已确认日期，不重复强制覆盖
            if (regTime == null || regTime.isBlank() || "自动抓取中...".equals(regTime)) {
                regTime = "自动抓取中...";
                acc.setRegisterTime("自动抓取中..."); 
                triggerAutoFetchRegisterTime(accountName);
            }

            var fp = ociCloudService.getClientFingerprint(accountName);
            if (fp != null) {
                accInfo.put("clientProfile", fp.profileName());
                accInfo.put("clientUserAgent", fp.userAgent());
                accInfo.put("clientInfo", fp.opcClientInfo());
                accInfo.put("clientLanguage", fp.acceptLanguage());
                accInfo.put("clientAccept", fp.accept());
                accInfo.put("clientEncoding", fp.acceptEncoding());
                accInfo.put("clientRequestIdStyle", fp.requestIdStyle());
                accInfo.put("clientTimeouts", fp.connectTimeoutMillis() + "ms / " + fp.readTimeoutMillis() + "ms");
                accInfo.put("clientPhaseOffset", fp.phaseOffsetMs() + "ms");
            }
            accInfo.put("clientIdentifier", acc.getClientIdentifier() != null ? acc.getClientIdentifier() : "");
            accInfo.put("proxy", acc.getProxy() != null ? acc.getProxy() : "");

            accInfo.put("registerTime", regTime);
            accInfo.put("isPayg", ociCloudService.getPaygStatusString(accountName));
            accInfo.put("billAmount", ociCloudService.getCachedCurrentMonthBill(accountName));
            accInfo.put("isBanned", String.valueOf(ociCloudService.isAccountBanned(accountName)));
            accInfo.put("tag", ociCloudService.getAccountTag(accountName));
            accountList.add(accInfo);
        }
        return accountList;
    }

    @PostMapping("/poll")
    public Map<String, Object> poll(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false);
        Map<String, OciProperties.Account> orderedAccounts = accountYamlService.reorderAccounts(ociProperties.getAccounts());
        List<Map<String, Object>> accountList = buildAccountDetailsList(orderedAccounts);

        Map<String, Map<String, Object>> trafficData = new HashMap<>();
        ociProperties.getAccounts().keySet().forEach(accName -> {
            TrafficLimit limit = trafficLimitRepository.findById(accName).orElse(new TrafficLimit());
            Map<String, Object> info = new HashMap<>();
            double currentUsage = ScheduledTasksService.TRAFFIC_CACHE.getOrDefault(accName, -1.0);
            info.put("usage_gb", currentUsage);
            info.put("limit_gb", limit.getLimitGb() != null ? limit.getLimitGb() : 0);
            String updateTime = ScheduledTasksService.UPDATE_TIME_CACHE.getOrDefault(accName, currentUsage < 0 ? "正在后台静默同步中..." : "从未同步");
            info.put("update_time", updateTime);
            info.put("isPayg", ociCloudService.getPaygStatusString(accName));
            trafficData.put(accName, info);
        });

        return Map.of(
            "success", true,
            "instances", getEnrichedInstances(),
            "accountDetails", accountList,
            "accounts", orderedAccounts.keySet(),
            "traffic", trafficData
        );
    }

    private final java.util.concurrent.atomic.AtomicBoolean isSyncingInstances = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean isSyncingAccounts = new java.util.concurrent.atomic.AtomicBoolean(false);

    @PostMapping("/sync")
    public Map<String, Object> syncInstances(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false);
        if (isSyncingInstances.compareAndSet(false, true)) {
            CompletableFuture.runAsync(() -> {
                try {
                    ociCloudService.updateInstancesCache(true);
                } finally {
                    isSyncingInstances.set(false);
                }
            });
        }
        return Map.of(
            "success", true, 
            "msg", "🔄 后台已开始强行拉取甲骨文最新数据及分配的 IP。页面正在自动无感检测更新...", 
            "instances", getEnrichedInstances()
        );
    }

    @PostMapping("/sync-accounts")
    public Map<String, Object> syncAccountsDetails(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false);
        if (!isSyncingAccounts.compareAndSet(false, true)) {
            return Map.of("success", true, "message", "后台正在执行全量同步中，请稍候等待页面自动更新数据");
        }
        ociCloudService.clearPaygCache();
        ociCloudService.clearBillCache();
        ociCloudService.clearBannedCache();
        ociCloudService.clearRegionsCache();
        ociCloudService.clearProviderCache();
        for (String accountName : ociProperties.getAccounts().keySet()) {
            ociCloudService.invalidateBootVolumeCache(accountName);
            OciProperties.Account acc = ociProperties.getAccounts().get(accountName);
            if (acc != null && !isValidRegisterTime(acc.getRegisterTime())) {
                acc.setRegisterTime("自动抓取中...");
                triggerAutoFetchRegisterTime(accountName);
            }
        }
        CompletableFuture.runAsync(() -> {
            try {
                ociCloudService.updateInstancesCache(true);
                for (String accountName : ociProperties.getAccounts().keySet()) {
                    ociCloudService.isAccountBanned(accountName);
                    ociCloudService.getCachedPaygStatus(accountName);
                    ociCloudService.getCachedCurrentMonthBill(accountName);
                    ociCloudService.getSubscribedRegions(accountName);
                    OciProperties.Account acc = ociProperties.getAccounts().get(accountName);
                    if (acc != null && !isValidRegisterTime(acc.getRegisterTime())) {
                        triggerAutoFetchRegisterTime(accountName);
                    }
                    try {
                        ociCloudService.getBootVolumes(accountName);
                    } catch (Exception ignored) {}
                }
            } finally {
                isSyncingAccounts.set(false);
            }
        });
        return Map.of("success", true, "message", "缓存已清理，后台已开始重新同步，页面将自动更新数据");
    }

    @PostMapping("/traffic")
    public Map<String, Object> getTraffic(@RequestBody Map<String, Object> payload) {
        Object tokenObj = payload.get("token");
        if (tokenObj == null || !checkAuth(String.valueOf(tokenObj))) return Map.of("success", false);
        
        Map<String, Map<String, Object>> trafficData = new HashMap<>();
        boolean force = payload.containsKey("force") && Boolean.TRUE.equals(payload.get("force"));
        Object targetAccObj = payload.get("targetAccount");
        String targetAccount = targetAccObj != null ? String.valueOf(targetAccObj) : null;

        if (force || ScheduledTasksService.TRAFFIC_CACHE.isEmpty()) {
            CompletableFuture.runAsync(() -> {
                String currentMonth = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
                Set<String> accountsToUpdate = (targetAccount != null && !targetAccount.isEmpty()) 
                                               ? Set.of(targetAccount) 
                                               : ociProperties.getAccounts().keySet();
                
                accountsToUpdate.forEach(accName -> {
                    try {
                        double usage = -1.0;
                        int maxRetries = 3;
                        for (int i = 0; i < maxRetries; i++) {
                            try {
                                usage = ociCloudService.fetchTrafficInGb(accName);
                                if (usage >= 0) break;
                            } catch (Exception ex) {
                                if (ex.getMessage() != null && ex.getMessage().contains("500") && ex.getMessage().contains("InternalError")) {
                                    if (i == maxRetries - 1) throw ex;
                                    try { Thread.sleep(4000 * (i + 1)); } catch (InterruptedException ignored) {}
                                } else {
                                    throw ex;
                                }
                            }
                        }

                        if (usage >= 0) {
                            ScheduledTasksService.TRAFFIC_CACHE.put(accName, usage);
                            ScheduledTasksService.UPDATE_TIME_CACHE.put(accName, java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " UTC");
                            
                            if (ScheduledTasksService.getInstance() != null) {
                                ScheduledTasksService.getInstance().checkAndSendTrafficWarning(accName, usage, currentMonth);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("获取流量失败 [" + accName + "]: " + e.getMessage());
                        if (!ScheduledTasksService.TRAFFIC_CACHE.containsKey(accName) || ScheduledTasksService.TRAFFIC_CACHE.get(accName) < 0) {
                            ScheduledTasksService.TRAFFIC_CACHE.put(accName, -1.0);
                            ScheduledTasksService.UPDATE_TIME_CACHE.put(accName, "API异常暂无数据，请稍后重试");
                        }
                    }
                });
            });
        }

        ociProperties.getAccounts().keySet().forEach(accName -> {
            TrafficLimit limit = trafficLimitRepository.findById(accName).orElse(new TrafficLimit());
            Map<String, Object> info = new HashMap<>();
            
            double currentUsage = ScheduledTasksService.TRAFFIC_CACHE.getOrDefault(accName, -1.0);
            info.put("usage_gb", currentUsage);
            info.put("limit_gb", limit.getLimitGb() != null ? limit.getLimitGb() : 0);
            
            String updateTime = ScheduledTasksService.UPDATE_TIME_CACHE.getOrDefault(accName, currentUsage < 0 ? "正在后台静默同步中..." : "从未同步");
            info.put("update_time", updateTime);
            info.put("isPayg", ociCloudService.getPaygStatusString(accName));
            
            trafficData.put(accName, info);
        });
        
        return Map.of("success", true, "traffic", trafficData);
    }
}
