package com.oci.ocimanager.controller.admin;

import com.oci.ocimanager.config.OciProperties;
import com.oci.ocimanager.entity.TrafficLimit;
import com.oci.ocimanager.repository.TrafficLimitRepository;
import com.oci.ocimanager.service.AccountYamlService;
import com.oci.ocimanager.service.AdminAuthService;
import com.oci.ocimanager.service.OciCloudService;
import com.oci.ocimanager.service.ScheduledTasksService;
import com.oci.ocimanager.service.TelegramBotService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired private AccountYamlService accountYamlService;
    @Autowired private AdminAuthService adminAuthService;
    @Autowired private com.oci.ocimanager.service.NotificationService notificationService;
    @Autowired private com.oci.ocimanager.service.SystemUpdateService systemUpdateService;

    private final ExecutorService ipFetchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService trafficRefreshExecutor = new ThreadPoolExecutor(
            4,
            4,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(128),
            r -> {
                Thread t = new Thread(r, "oci-traffic-refresh");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy()
    );
    private final Set<String> fetchingIpOcids = ConcurrentHashMap.newKeySet();
    private final Set<String> refreshingTrafficAccounts = ConcurrentHashMap.newKeySet();

    @PreDestroy
    public void destroy() {
        ipFetchExecutor.shutdownNow();
        trafficRefreshExecutor.shutdownNow();
    }

    private boolean checkAuth(HttpServletRequest request, Map<String, ?> payload) {
        return adminAuthService.checkAuth(request, payload);
    }

    private Map<String, OciProperties.Account> snapshotOrderedAccounts() {
        synchronized (accountYamlService.lock()) {
            Map<String, OciProperties.Account> current = ociProperties.getAccounts();
            return current != null ? new LinkedHashMap<>(current) : new LinkedHashMap<>();
        }
    }

    /**
     * 零阻塞组装实例视图：直接读取内存缓存快照与本地 Region 地理映射；
     * 若运行中实例尚未缓存 IP，仅单飞投递后台异步抓取任务，绝不在高频 /poll 线程内同步阻塞等待！
     */
    private Map<String, Map<String, String>> getEnrichedInstances() {
        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        if (instances.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, Map<String, String>> enriched = new LinkedHashMap<>();

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
            } else if ("RUNNING".equalsIgnoreCase(info.get("state"))) {
                info.put("ip", "获取中...");
                String account = info.get("account");
                if (account != null && fetchingIpOcids.add(ocid)) {
                    CompletableFuture.runAsync(() -> {
                        try {
                            ociCloudService.getInstanceIp(ocid, account);
                            ociCloudService.getInstanceIpv6(ocid, account);
                        } catch (Exception ignored) {
                        } finally {
                            fetchingIpOcids.remove(ocid);
                        }
                    }, ipFetchExecutor);
                }
            } else {
                info.put("ip", "未分配 IP");
            }

            String ip = info.get("ip");
            info.put("location", ociCloudService.getInstanceLocation(ip, info.get("region")));
            if (ip != null && (ip.contains("(NAT)") || ip.contains("500M") || ip.contains("(原)"))) {
                info.put("is500m", "true");
            } else {
                info.put("is500m", "false");
            }
            enriched.put(ocid, info);
        }

        return enriched;
    }

    @PostMapping("/data")
    public Map<String, Object> getData(HttpServletRequest request, @RequestBody(required = false) Map<String, String> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");

        // 🔒 只读快照：绝不在读接口中调用无锁 ociProperties.setAccounts(...)，彻底杜绝并发删号被复活！
        Map<String, OciProperties.Account> orderedAccounts = snapshotOrderedAccounts();
        List<Map<String, Object>> accountList = buildAccountDetailsList(orderedAccounts);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("instances", getEnrichedInstances());
        result.put("accounts", orderedAccounts.keySet());
        result.put("accountDetails", accountList);
        result.put("tgBotRunning", telegramBotService.isBotRunning());
        result.put("unreadNoticeCount", notificationService.getUnreadCount());
        result.put("updateInfo", systemUpdateService.getCachedUpdateInfo());
        return result;
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
            accInfo.put("subscribedRegions", String.join(",", ociCloudService.getCachedSubscribedRegions(accountName)));
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
            accInfo.put("proxy", com.oci.ocimanager.service.OciClientWrapper.redactProxy(acc.getProxy()));

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
    public Map<String, Object> poll(HttpServletRequest request, @RequestBody(required = false) Map<String, String> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        Map<String, OciProperties.Account> orderedAccounts = snapshotOrderedAccounts();
        List<Map<String, Object>> accountList = buildAccountDetailsList(orderedAccounts);

        Map<String, Map<String, Object>> trafficData = new HashMap<>();
        orderedAccounts.keySet().forEach(accName -> {
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

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("instances", getEnrichedInstances());
        result.put("accountDetails", accountList);
        result.put("accounts", orderedAccounts.keySet());
        result.put("traffic", trafficData);
        result.put("unreadNoticeCount", notificationService.getUnreadCount());
        result.put("latestNotices", notificationService.getLatestUnreadNotices(3));
        result.put("updateInfo", systemUpdateService.getCachedUpdateInfo());
        return result;
    }

    private final java.util.concurrent.atomic.AtomicBoolean isSyncingInstances = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean isSyncingAccounts = new java.util.concurrent.atomic.AtomicBoolean(false);

    @PostMapping("/sync")
    public Map<String, Object> syncInstances(HttpServletRequest request, @RequestBody(required = false) Map<String, String> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
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
    public Map<String, Object> syncAccountsDetails(HttpServletRequest request, @RequestBody(required = false) Map<String, String> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        if (!isSyncingAccounts.compareAndSet(false, true)) {
            return Map.of("success", true, "message", "后台正在执行全量同步中，请稍候等待页面自动更新数据");
        }
        Map<String, OciProperties.Account> accountsSnapshot = snapshotOrderedAccounts();
        ociCloudService.clearPaygCache();
        ociCloudService.clearBillCache();
        ociCloudService.clearBannedCache();
        ociCloudService.clearRegionsCache();
        ociCloudService.clearProviderCache();
        for (Map.Entry<String, OciProperties.Account> entry : accountsSnapshot.entrySet()) {
            String accountName = entry.getKey();
            OciProperties.Account acc = entry.getValue();
            ociCloudService.invalidateBootVolumeCache(accountName);
            if (acc != null && !isValidRegisterTime(acc.getRegisterTime())) {
                acc.setRegisterTime("自动抓取中...");
                triggerAutoFetchRegisterTime(accountName);
            }
        }
        CompletableFuture.runAsync(() -> {
            try {
                ociCloudService.updateInstancesCache(true);
                Map<String, OciProperties.Account> asyncSnapshot = snapshotOrderedAccounts();
                for (Map.Entry<String, OciProperties.Account> entry : asyncSnapshot.entrySet()) {
                    String accountName = entry.getKey();
                    OciProperties.Account acc = entry.getValue();
                    ociCloudService.isAccountBanned(accountName);
                    ociCloudService.getCachedPaygStatus(accountName);
                    ociCloudService.getCachedCurrentMonthBill(accountName);
                    ociCloudService.getSubscribedRegions(accountName);
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
    public Map<String, Object> getTraffic(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        
        Map<String, Map<String, Object>> trafficData = new HashMap<>();
        boolean force = payload != null && payload.containsKey("force") && Boolean.TRUE.equals(payload.get("force"));
        Object targetAccObj = payload != null ? payload.get("targetAccount") : null;
        String targetAccount = targetAccObj != null ? String.valueOf(targetAccObj) : null;

        Map<String, OciProperties.Account> currentAccounts = snapshotOrderedAccounts();
        if (force || ScheduledTasksService.TRAFFIC_CACHE.isEmpty()) {
            String currentMonth = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
            Set<String> accountsToUpdate;
            if (targetAccount != null && !targetAccount.isEmpty()) {
                accountsToUpdate = currentAccounts.containsKey(targetAccount) ? Set.of(targetAccount) : Collections.emptySet();
            } else {
                accountsToUpdate = currentAccounts.keySet();
            }

            for (String accName : accountsToUpdate) {
                if (!refreshingTrafficAccounts.add(accName)) {
                    continue; // 单飞去重：该账号已在后台刷新流量中，忽略重复请求
                }
                try {
                    trafficRefreshExecutor.execute(() -> {
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
                                        try { Thread.sleep(4000L * (i + 1)); } catch (InterruptedException ignored) {}
                                    } else {
                                        throw ex;
                                    }
                                }
                            }

                            if (usage >= 0) {
                                if (ScheduledTasksService.getInstance() != null) {
                                    ScheduledTasksService.getInstance().evaluateAndRecordTrafficUsage(accName, usage, currentMonth);
                                } else {
                                    ScheduledTasksService.TRAFFIC_CACHE.put(accName, usage);
                                    ScheduledTasksService.UPDATE_TIME_CACHE.put(accName,
                                            java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " UTC");
                                }
                            } else if (!ScheduledTasksService.TRAFFIC_CACHE.containsKey(accName) || ScheduledTasksService.TRAFFIC_CACHE.get(accName) < 0) {
                                ScheduledTasksService.TRAFFIC_CACHE.put(accName, -1.0);
                                ScheduledTasksService.UPDATE_TIME_CACHE.put(accName, "API异常暂无数据，请稍后重试");
                            }
                        } catch (Exception e) {
                            System.err.println("获取流量失败 [" + accName + "]: " + e.getMessage());
                            if (!ScheduledTasksService.TRAFFIC_CACHE.containsKey(accName) || ScheduledTasksService.TRAFFIC_CACHE.get(accName) < 0) {
                                ScheduledTasksService.TRAFFIC_CACHE.put(accName, -1.0);
                                ScheduledTasksService.UPDATE_TIME_CACHE.put(accName, "API异常暂无数据，请稍后重试");
                            }
                        } finally {
                            refreshingTrafficAccounts.remove(accName);
                        }
                    });
                } catch (Exception rejectedEx) {
                    refreshingTrafficAccounts.remove(accName);
                }
            }
        }

        currentAccounts.keySet().forEach(accName -> {
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

    @PostMapping("/set-traffic-limit")
    public Map<String, Object> setTrafficLimit(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");

        Object rawAccObj = payload.get("account");
        String rawAcc = rawAccObj != null ? String.valueOf(rawAccObj).trim() : "";
        if (rawAcc.isEmpty()) {
            return Map.of("success", false, "error", "账号名不能为空");
        }

        Object rawLimitObj = payload.containsKey("limit_gb") ? payload.get("limit_gb") : payload.get("limitGb");
        if (rawLimitObj == null) {
            return Map.of("success", false, "error", "缺少流量限额参数 (limit_gb)");
        }

        int limitGb;
        try {
            double parsed = Double.parseDouble(String.valueOf(rawLimitObj).trim());
            if (Double.isNaN(parsed) || Double.isInfinite(parsed) || parsed < 0 || parsed > 10_000_000) {
                return Map.of("success", false, "error", "流量限额必须为 0 ~ 10,000,000 之间的有效整数 (0 表示不限流)");
            }
            limitGb = (int) Math.round(parsed);
        } catch (NumberFormatException e) {
            return Map.of("success", false, "error", "流量限额格式无效，请输入非负整数");
        }

        Map<String, OciProperties.Account> accounts = snapshotOrderedAccounts();
        String matchedAccount = null;
        if (accounts.containsKey(rawAcc)) {
            matchedAccount = rawAcc;
        } else {
            String cleanReq = AccountYamlService.normalizeAccountKey(rawAcc);
            for (String key : accounts.keySet()) {
                if (AccountYamlService.normalizeAccountKey(key).equals(cleanReq)) {
                    matchedAccount = key;
                    break;
                }
            }
        }
        if (matchedAccount == null) {
            return Map.of("success", false, "error", "账号不存在: " + rawAcc);
        }

        TrafficLimit entity = trafficLimitRepository.findById(matchedAccount).orElseGet(TrafficLimit::new);
        entity.setAccountName(matchedAccount);
        entity.setLimitGb(limitGb);
        trafficLimitRepository.save(entity);

        final String finalAccount = matchedAccount;
        CompletableFuture.runAsync(() -> {
            try {
                ScheduledTasksService scheduler = ScheduledTasksService.getInstance();
                if (scheduler == null) return;
                String currentMonth = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
                Double cachedUsage = ScheduledTasksService.TRAFFIC_CACHE.get(finalAccount);
                if (cachedUsage != null && cachedUsage >= 0) {
                    scheduler.evaluateTrafficMeltdownOrRevive(finalAccount, cachedUsage, currentMonth, true);
                } else {
                    double fresh = ociCloudService.fetchTrafficInGb(finalAccount);
                    if (fresh >= 0) {
                        scheduler.evaluateAndRecordTrafficUsage(finalAccount, fresh, currentMonth);
                    }
                }
            } catch (Exception e) {
                System.err.println("更新流量限额后即时评估熔断状态异常 [" + finalAccount + "]: " + e.getMessage());
            }
        }, ipFetchExecutor);

        String msg = limitGb > 0
                ? ("已成功设置账号 [" + matchedAccount + "] 月流量熔断限额为 " + limitGb + " GB")
                : ("已取消账号 [" + matchedAccount + "] 的月流量熔断限额");
        return Map.of("success", true, "account", matchedAccount, "limit_gb", limitGb, "message", msg);
    }
}
