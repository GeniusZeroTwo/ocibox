package com.oci.ocimanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oci.ocimanager.config.OciProperties;
import com.oci.ocimanager.entity.SystemConfig;
import com.oci.ocimanager.repository.SystemConfigRepository;
import com.oracle.bmc.core.requests.ListInstancesRequest;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.requests.GetCompartmentRequest;
import com.oracle.bmc.identity.requests.GetUserRequest;
import com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest;
import com.oracle.bmc.identity.requests.ListUsersRequest;
import com.oracle.bmc.monitoring.model.SummarizeMetricsDataDetails;
import com.oracle.bmc.monitoring.requests.SummarizeMetricsDataRequest;

import com.oracle.bmc.ospgateway.model.Subscription;
import com.oracle.bmc.ospgateway.model.SubscriptionSummary;
import com.oracle.bmc.ospgateway.requests.GetSubscriptionRequest;
import com.oracle.bmc.ospgateway.requests.ListSubscriptionsRequest;
import com.oracle.bmc.usageapi.model.RequestSummarizedUsagesDetails;
import com.oracle.bmc.usageapi.model.UsageSummary;
import com.oracle.bmc.usageapi.requests.RequestSummarizedUsagesRequest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 账单/账号状态域服务：当月账单与汇率、月流量统计、PAYG 识别、封禁检测、账户标签、租户注册时间。
 * 结构重构自 OciCloudService（门面保留原对外方法并委托到此处）。
 */
@Service
public class OciAccountBillingService {

    @Autowired
    private OciProperties ociProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    @Autowired
    private OciClientManagerService clientManager;


    @Autowired
    private AccountYamlService accountYamlService;

    private static final Set<String> ACTIVE_REG_FETCH_TASKS = ConcurrentHashMap.newKeySet();

    public boolean isValidRegisterTime(String time) {
        return time != null && time.trim().matches("\\d{4}-\\d{2}-\\d{2}");
    }

    private final Map<String, String> billCache = new ConcurrentHashMap<>();
    private final Map<String, Long> billCacheTime = new ConcurrentHashMap<>();
    private final Set<String> fetchingBillAccounts = ConcurrentHashMap.newKeySet();

    private final Map<String, Boolean> paygCache = new ConcurrentHashMap<>();
    private final Map<String, Long> paygCheckTime = new ConcurrentHashMap<>();
    private final Set<String> fetchingPaygAccounts = ConcurrentHashMap.newKeySet();

    private final ExecutorService paygExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PaygCheck-Worker");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        loadBillsFromDatabase();
        if (!billCache.isEmpty()) {
            System.out.println("📦 [账单缓存恢复] 成功从本地缓存恢复 " + billCache.size() + " 个账号的历史账单数据。");
        }

        // 🌟 自动升级校准：检测算法升级为权威多维校准（OSP+存储/计算配额Limits+多区域+真实扣费账单+运行机型+手动锁定保护）。
        // 若数据库中尚未打上权威校准标记，则清洗历史自动探测缓存，重新精准校准。
        String algoVer = null;
        try {
            algoVer = systemConfigRepository.findById("PAYG_ALGO_VERSION").map(SystemConfig::getConfigValue).orElse(null);
        } catch (Exception ignored) {}

        if (!"v9_accurate_osp_multi_sub".equals(algoVer)) {
            System.out.println("🔄 [PAYG算法升级校准] 清除旧版缓存与误判数据，升级为全量订阅权威精准探测...");
            purgeContaminatedPaygCache();
            try {
                systemConfigRepository.save(new SystemConfig("PAYG_ALGO_VERSION", "v9_accurate_osp_multi_sub"));
            } catch (Exception ignored) {}
        } else {
            loadPaygFromStorage();
        }

        // 启动后异步对所有账号执行权威探测校准
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(2000);
                System.out.println("💳 [PAYG全量校准] 启动所有账号的权威自动探测与校准 (排队匀速执行，防429限流)...");
                for (String accountName : ociProperties.getAccounts().keySet()) {
                    enqueuePaygCheck(accountName);
                }
            } catch (Exception ignored) {}
        });
    }

    public void purgeContaminatedPaygCache() {
        try {
            List<SystemConfig> allConfigs = systemConfigRepository.findAll();
            List<String> keysToDelete = new ArrayList<>();
            for (SystemConfig cfg : allConfigs) {
                String k = cfg.getConfigKey();
                if (k != null && (k.startsWith("PAYG_STATUS_") || k.startsWith("PAYG_TENANCY_") || k.startsWith("PAYG_MANUAL_"))) {
                    keysToDelete.add(k);
                }
            }
            if (!keysToDelete.isEmpty()) {
                systemConfigRepository.deleteAllById(keysToDelete);
                System.out.println("🧹 [PAYG脏数据清洗] 已彻底清除 SQLite 中 " + keysToDelete.size() + " 条历史旧版缓存及手动锁定记录。");
            }
            // SQLite 纯轻量模式无 Redis
        } catch (Exception e) {
            System.err.println("清洗 PAYG 旧缓存异常: " + e.getMessage());
        }
        paygCache.clear();
        paygCheckTime.clear();
    }

    @PreDestroy
    public void destroy() {
        try {
            paygExecutor.shutdown();
        } catch (Exception ignored) {}
    }
    private final Map<String, Boolean> bannedCache = new ConcurrentHashMap<>();
    private final Map<String, Long> bannedCacheTime = new ConcurrentHashMap<>();

    public void recordAccountSuccess(String accountName) {
        if (accountName != null) {
            bannedCache.put(accountName, false);
            bannedCacheTime.put(accountName, System.currentTimeMillis());
        }
    }

    public boolean isAccountBanned(String accountName) {
        if (accountName == null) return false;
        long now = System.currentTimeMillis();
        Long cachedTime = bannedCacheTime.get(accountName);
        Boolean cachedStatus = bannedCache.get(accountName);

        if (cachedStatus != null && cachedTime != null) {
            // 正常账号缓存 5 分钟 (300,000ms)，Auth Error 状态仅缓存 30 秒 (30,000ms) 以便于瞬时网络恢复后快速自动纠错
            long expireMs = cachedStatus ? 30_000L : 300_000L;
            if (now - cachedTime < expireMs) {
                return cachedStatus;
            }
        }

        bannedCacheTime.put(accountName, now);
        if (cachedStatus == null) {
            bannedCache.put(accountName, false);
        }

        CompletableFuture.runAsync(() -> {
            checkAndCacheAccountBannedStatus(accountName);
        });

        return bannedCache.getOrDefault(accountName, false);
    }

    private void checkAndCacheAccountBannedStatus(String accountName) {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(OciCloudService.class.getClassLoader());
            OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
            if (accConfig == null) return;

            boolean authFailed = false;
            try {
                var clients = clientManager.getOciClients(accountName);
                clients.getIdentity().listAvailabilityDomains(com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest.builder().compartmentId(accConfig.getTenancy()).build());
            } catch (com.oracle.bmc.model.BmcException e) {
                if (e.getStatusCode() == 401 || (e.getMessage() != null && e.getMessage().contains("NotAuthenticated"))) {
                    authFailed = true;
                }
            }

            // 🌟 二次验证：防止因单次网络抖动、密钥刚更新或 Client 缓存过期导致误判 Auth Error
            if (authFailed) {
                try {
                    Thread.sleep(500);
                    clientManager.clearClientCacheForAccount(accountName);
                    var retryClients = clientManager.getOciClients(accountName);
                    retryClients.getIdentity().listAvailabilityDomains(com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest.builder().compartmentId(accConfig.getTenancy()).build());
                    authFailed = false; // 重试成功，说明并非封禁
                } catch (com.oracle.bmc.model.BmcException retryEx) {
                    if (retryEx.getStatusCode() == 401 || (retryEx.getMessage() != null && retryEx.getMessage().contains("NotAuthenticated"))) {
                        authFailed = true;
                    } else {
                        authFailed = false;
                    }
                } catch (Exception retryEx) {
                    authFailed = false;
                }
            }

            if (authFailed) {
                bannedCache.put(accountName, true);
            } else {
                bannedCache.put(accountName, false);
            }
            bannedCacheTime.put(accountName, System.currentTimeMillis());
        } catch (Exception e) {
            bannedCache.put(accountName, false);
            bannedCacheTime.put(accountName, System.currentTimeMillis());
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    private final Map<String, String> accountTagCache = new ConcurrentHashMap<>();

    public String getAccountTag(String accountName) {
        if (accountName == null || accountName.isEmpty()) return "";
        if (accountTagCache.containsKey(accountName)) {
            return accountTagCache.get(accountName);
        }
        String dbKey = "ACCOUNT_TAG_" + accountName;
        try {
            java.util.Optional<SystemConfig> dbOpt = systemConfigRepository.findById(dbKey);
            if (dbOpt.isPresent() && dbOpt.get().getConfigValue() != null) {
                String tag = dbOpt.get().getConfigValue();
                accountTagCache.put(accountName, tag);
                return tag;
            }
        } catch (Exception ignored) {}
        return "";
    }

    public void setAccountTag(String accountName, String tag) {
        if (accountName == null || accountName.isEmpty()) return;
        String cleanTag = tag == null ? "" : tag.trim();
        String dbKey = "ACCOUNT_TAG_" + accountName;
        accountTagCache.put(accountName, cleanTag);
        try {
            if (cleanTag.isEmpty()) {
                systemConfigRepository.deleteById(dbKey);
            } else {
                systemConfigRepository.save(new SystemConfig(dbKey, cleanTag));
            }
        } catch (Exception e) {
            System.err.println("保存账户标签异常 [" + accountName + "]: " + e.getMessage());
        }
    }

    public void clearTagCache() {
        accountTagCache.clear();
    }

    public void clearBannedCache() {
        bannedCache.clear();
        bannedCacheTime.clear();
    }

    public String cleanAccountName(String accountName) {
        if (accountName == null) return "";
        return accountName.replaceAll("[\"'\\s\\[\\]\\r\\n]", "");
    }

    public OciProperties.Account findAccountConfig(String accountName) {
        if (accountName == null) return null;
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        if (accConfig != null) return accConfig;
        String clean = cleanAccountName(accountName);
        for (Map.Entry<String, OciProperties.Account> e : ociProperties.getAccounts().entrySet()) {
            if (cleanAccountName(e.getKey()).equalsIgnoreCase(clean)) {
                return e.getValue();
            }
        }
        return null;
    }

    public void loadPaygFromStorage() {
        // 1. 从 SQLite 加载自动识别的 PAYG 状态
        try {
            List<SystemConfig> allConfigs = systemConfigRepository.findAll();
            for (SystemConfig cfg : allConfigs) {
                String key = cfg.getConfigKey();
                String val = cfg.getConfigValue();
                if (key == null || val == null) continue;
                boolean boolVal = Boolean.parseBoolean(val);
                if (key.startsWith("PAYG_TENANCY_")) {
                    String tenancy = key.substring("PAYG_TENANCY_".length());
                    paygCache.put(tenancy, boolVal);
                } else if (key.startsWith("PAYG_STATUS_TENANCY_")) {
                    String tenancy = key.substring("PAYG_STATUS_TENANCY_".length());
                    paygCache.put(tenancy, boolVal);
                } else if (key.startsWith("PAYG_STATUS_")) {
                    String acc = key.substring("PAYG_STATUS_".length());
                    paygCache.put(acc, boolVal);
                } else if (key.startsWith("PAYG_MANUAL_")) {
                    String acc = key.substring("PAYG_MANUAL_".length());
                    paygCache.put(acc, boolVal);
                }
            }
        } catch (Exception e) {
            System.err.println("从 SQLite 载入 PAYG 状态失败: " + e.getMessage());
        }

        // 2. 状态已直接由 SQLite 恢复

        // 3. 关联配置中的所有账号并预热缓存
        int paygCount = 0;
        int freeCount = 0;
        for (Map.Entry<String, OciProperties.Account> entry : ociProperties.getAccounts().entrySet()) {
            String accName = entry.getKey();
            String cleanName = cleanAccountName(accName);
            String tenancy = entry.getValue() != null ? entry.getValue().getTenancy() : null;

            Boolean isPayg = null;
            if (tenancy != null && paygCache.containsKey(tenancy)) {
                isPayg = paygCache.get(tenancy);
            } else if (paygCache.containsKey(accName)) {
                isPayg = paygCache.get(accName);
            } else if (paygCache.containsKey(cleanName)) {
                isPayg = paygCache.get(cleanName);
            }

            if (isPayg != null) {
                paygCache.put(accName, isPayg);
                paygCache.put(cleanName, isPayg);
                if (tenancy != null) paygCache.put(tenancy, isPayg);
                if (isPayg) paygCount++;
                else freeCount++;
            }
        }
        System.out.println("💳 [PAYG状态初始化] 成功恢复 " + (paygCount + freeCount) + " 个账号状态 (升级号 PAYG: " + paygCount + " 个, 免费号: " + freeCount + " 个)");
    }

    private void persistPaygToRedis() { /* SQLite 自动落盘 */ }

    public void savePaygStatus(String accountName, String tenancyId, boolean isPayg) {
        String cleanName = cleanAccountName(accountName);
        paygCache.put(accountName, isPayg);
        paygCache.put(cleanName, isPayg);
        if (tenancyId != null && !tenancyId.isBlank()) {
            paygCache.put(tenancyId, isPayg);
        }
        paygCheckTime.put(accountName, System.currentTimeMillis());

        try {
            if (tenancyId != null && !tenancyId.isBlank()) {
                systemConfigRepository.save(new SystemConfig("PAYG_TENANCY_" + tenancyId, String.valueOf(isPayg)));
                systemConfigRepository.save(new SystemConfig("PAYG_STATUS_TENANCY_" + tenancyId, String.valueOf(isPayg)));
            }
            systemConfigRepository.save(new SystemConfig("PAYG_STATUS_" + cleanName, String.valueOf(isPayg)));
            systemConfigRepository.save(new SystemConfig("PAYG_STATUS_" + accountName, String.valueOf(isPayg)));
        } catch (Exception e) {
            System.err.println("保存 PAYG 状态到 SQLite 失败: " + e.getMessage());
        }

        persistPaygToRedis();
    }

    public boolean isManualPayg(String accountName) {
        if (accountName == null) return false;
        String cleanName = cleanAccountName(accountName);
        try {
            return systemConfigRepository.existsById("PAYG_MANUAL_" + cleanName)
                || systemConfigRepository.existsById("PAYG_MANUAL_" + accountName);
        } catch (Exception e) {
            return false;
        }
    }

    public Boolean getManualPaygValue(String accountName) {
        if (accountName == null) return null;
        String cleanName = cleanAccountName(accountName);
        try {
            var cfg = systemConfigRepository.findById("PAYG_MANUAL_" + cleanName);
            if (cfg.isPresent()) return Boolean.parseBoolean(cfg.get().getConfigValue());
            var cfg2 = systemConfigRepository.findById("PAYG_MANUAL_" + accountName);
            if (cfg2.isPresent()) return Boolean.parseBoolean(cfg2.get().getConfigValue());
        } catch (Exception ignored) {}
        return null;
    }

    public void setManualPaygStatus(String accountName, boolean isPayg) {
        if (accountName == null) return;
        String cleanName = cleanAccountName(accountName);
        OciProperties.Account accConfig = findAccountConfig(accountName);
        String tenancyId = accConfig != null ? accConfig.getTenancy() : null;

        try {
            systemConfigRepository.save(new SystemConfig("PAYG_MANUAL_" + cleanName, String.valueOf(isPayg)));
            systemConfigRepository.save(new SystemConfig("PAYG_MANUAL_" + accountName, String.valueOf(isPayg)));
        } catch (Exception e) {
            System.err.println("保存 PAYG 手动配置到 SQLite 失败: " + e.getMessage());
        }

        savePaygStatus(accountName, tenancyId, isPayg);
        System.out.println("🔧 [PAYG手动设置] 管理员手动指定账号 [" + accountName + "] -> " + (isPayg ? "升级号 (PAYG)" : "免费号 (FreeTier)"));
    }

    public void resetAccountPaygManualOverride(String accountName) {
        if (accountName == null) return;
        String cleanName = cleanAccountName(accountName);
        try {
            systemConfigRepository.deleteById("PAYG_MANUAL_" + cleanName);
            systemConfigRepository.deleteById("PAYG_MANUAL_" + accountName);
        } catch (Exception ignored) {}
        paygCache.remove(accountName);
        paygCache.remove(cleanName);
        paygCheckTime.remove(accountName);
        enqueuePaygCheck(accountName);
        System.out.println("🔄 [PAYG手动重置] 账号 [" + accountName + "] 已解除手动锁定，重置为全自动探测模式");
    }

    public boolean getCachedPaygStatus(String accountName) {
        if (accountName == null) return false;
        OciProperties.Account accConfig = findAccountConfig(accountName);
        String tenancy = accConfig != null ? accConfig.getTenancy() : null;
        String cleanName = cleanAccountName(accountName);

        if (tenancy != null && paygCache.containsKey(tenancy)) return paygCache.get(tenancy);
        if (paygCache.containsKey(accountName)) return paygCache.get(accountName);
        if (paygCache.containsKey(cleanName)) return paygCache.get(cleanName);

        enqueuePaygCheck(accountName);
        return false;
    }

    public String getPaygStatusString(String accountName) {
        if (accountName == null) return "false";
        OciProperties.Account accConfig = findAccountConfig(accountName);
        String tenancy = accConfig != null ? accConfig.getTenancy() : null;
        String cleanName = cleanAccountName(accountName);

        if (tenancy != null && paygCache.containsKey(tenancy)) return String.valueOf(paygCache.get(tenancy));
        if (paygCache.containsKey(accountName)) return String.valueOf(paygCache.get(accountName));
        if (paygCache.containsKey(cleanName)) return String.valueOf(paygCache.get(cleanName));

        if (fetchingPaygAccounts.contains(accountName) || fetchingPaygAccounts.contains(cleanName)) {
            return "checking";
        }

        // 🌟 防死循环：检查冷却时间，如果此前刚刚检测过（5分钟内），不要无限制重复排队与返回 checking
        Long lastCheck = paygCheckTime.get(accountName);
        if (lastCheck != null && (System.currentTimeMillis() - lastCheck < 300_000L)) {
            boolean fallback = paygCache.getOrDefault(accountName, paygCache.getOrDefault(cleanName, false));
            return String.valueOf(fallback);
        }

        enqueuePaygCheck(accountName);
        return "checking";
    }

    public void enqueuePaygCheck(String accountName) {
        if (accountName == null || accountName.isBlank()) return;
        if (!fetchingPaygAccounts.add(accountName)) {
            return;
        }
        paygExecutor.submit(() -> {
            try {
                // 匀速步调：每次检测前平滑间隔 1500ms，杜绝并发冲击 OCI API 触发 429
                Thread.sleep(1500);
                doFetchAndCheckPaygStatus(accountName);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("PAYG 异步检测任务异常 [" + accountName + "]: " + e.getMessage());
            } finally {
                fetchingPaygAccounts.remove(accountName);
            }
        });
    }

    private void doFetchAndCheckPaygStatus(String accountName) {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        OciClientWrapper clients = null;
        try {
            Thread.currentThread().setContextClassLoader(OciCloudService.class.getClassLoader());
            OciProperties.Account accConfig = findAccountConfig(accountName);
            if (accConfig == null) return;
            String tenancyId = accConfig.getTenancy();

            clients = clientManager.getOciClients(accountName);

            String homeRegion = null;
            List<String> subscribedRegions = new ArrayList<>();
            try {
                var regionSubs = clients.getIdentity().listRegionSubscriptions(
                    com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest.builder()
                        .tenancyId(tenancyId)
                        .build()
                ).getItems();
                if (regionSubs != null) {
                    for (var rs : regionSubs) {
                        if (rs.getRegionName() != null && !rs.getRegionName().isBlank()) {
                            subscribedRegions.add(rs.getRegionName());
                        }
                        if (Boolean.TRUE.equals(rs.getIsHomeRegion())) {
                            homeRegion = rs.getRegionName();
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (homeRegion == null) {
                homeRegion = accConfig.getRegion();
            }

            Boolean detectedPayg = null;
            String detectionSource = null;

            // =========================================================================
            // 【核心主维 1】: 多区域订阅 (Multi-Region Subscriptions - 100% 物理铁证)
            // 原理: 免费号在甲骨文平台物理上仅能订阅 1 个主区域 (Home Region)。
            // 免费号在控制台或 API 均被禁止开通第二区域。只要已开通订阅的区域数 > 1，100% 必定为付费升级号 (PAYG)。
            // =========================================================================
            if (subscribedRegions.size() > 1) {
                detectedPayg = true;
                detectionSource = "已开通多区域订阅 (" + subscribedRegions.size() + " 个区域: " + String.join(", ", subscribedRegions) + ")";
            }

            // =========================================================================
            // 【核心主维 2】: 甲骨文官方 OSP 个人订阅网关 (OSP Gateway - 官方权威凭据)
            // 原理: 甲骨文个人/普通商业账户在控制台升级时，由 OSP Gateway 统一管理订阅。
            // 在 Home Region 端点查询 ListSubscriptions + GetSubscription：
            //   - planType: FreeTier ("FREE_TIER") vs Payg ("PAYG")
            //   - upgradeState: Promo ("PROMO") vs Upgraded ("UPGRADED")
            // 只要 planType == Payg 或 upgradeState == Upgraded，100% 确定为升级号 (true)；
            // 只要 planType == FreeTier 或 upgradeState == Promo，100% 确定为免费号 (false)！
            // 遇到 429 限流时退避 2500ms 重试一次。
            // =========================================================================
            if (detectedPayg == null && homeRegion != null && !homeRegion.isBlank()) {
                try {
                    var ospClient = clients.getOspSubscriptionClientForRegion(homeRegion);
                    var listReq = ListSubscriptionsRequest.builder()
                        .compartmentId(tenancyId)
                        .ospHomeRegion(homeRegion)
                        .build();

                    var listResp = (com.oracle.bmc.ospgateway.responses.ListSubscriptionsResponse) null;
                    try {
                        listResp = ospClient.listSubscriptions(listReq);
                    } catch (com.oracle.bmc.model.BmcException bmcEx) {
                        if (bmcEx.getStatusCode() == 429) {
                            Thread.sleep(2500);
                            listResp = ospClient.listSubscriptions(listReq);
                        } else {
                            throw bmcEx;
                        }
                    }

                    if (listResp != null && listResp.getSubscriptionCollection() != null) {
                        var items = listResp.getSubscriptionCollection().getItems();
                        if (items != null && !items.isEmpty()) {
                            boolean foundPayg = false;
                            boolean hasFreeTierRecord = false;
                            String paygReason = null;
                            String freeReason = null;

                            for (SubscriptionSummary item : items) {
                                Subscription subscription = null;
                                try {
                                    var getReq = GetSubscriptionRequest.builder()
                                        .subscriptionId(item.getId())
                                        .compartmentId(tenancyId)
                                        .ospHomeRegion(homeRegion)
                                        .build();
                                    try {
                                        var getResp = ospClient.getSubscription(getReq);
                                        if (getResp != null) {
                                            subscription = getResp.getSubscription();
                                        }
                                    } catch (com.oracle.bmc.model.BmcException bmcEx) {
                                        if (bmcEx.getStatusCode() == 429) {
                                            Thread.sleep(2500);
                                            var getResp = ospClient.getSubscription(getReq);
                                            if (getResp != null) {
                                                subscription = getResp.getSubscription();
                                            }
                                        } else {
                                            throw bmcEx;
                                        }
                                    }
                                } catch (Exception ignored) {}

                                // 优先取 GetSubscription 详情，保底取 ListSubscriptions 概要
                                String planTypeVal = null;
                                String upgradeStateVal = null;
                                Date timeStart = null;
                                Date timePlanUpgrade = null;

                                if (subscription != null) {
                                    if (subscription.getPlanType() != null) planTypeVal = subscription.getPlanType().getValue();
                                    if (subscription.getUpgradeState() != null) upgradeStateVal = subscription.getUpgradeState().getValue();
                                    timeStart = subscription.getTimeStart();
                                    timePlanUpgrade = subscription.getTimePlanUpgrade();
                                } else {
                                    if (item.getPlanType() != null) planTypeVal = item.getPlanType().getValue();
                                    if (item.getUpgradeState() != null) upgradeStateVal = item.getUpgradeState().getValue();
                                    timeStart = item.getTimeStart();
                                    timePlanUpgrade = item.getTimePlanUpgrade();
                                }

                                // 顺带自动补全/校准注册时间 (若账号尚无有效注册时间)
                                if (timeStart != null) {
                                    try {
                                        OciProperties.Account targetAcc = ociProperties.getAccounts().get(accountName);
                                        if (targetAcc != null && !isValidRegisterTime(targetAcc.getRegisterTime())) {
                                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                                            String formattedTime = sdf.format(timeStart);
                                            targetAcc.setRegisterTime(formattedTime);
                                            if (accountYamlService != null) {
                                                accountYamlService.persistRegisterTimeToYaml(accountName, formattedTime);
                                            }
                                        }
                                    } catch (Exception ignored) {}
                                }

                                // 🌟 权威升级判定铁证：
                                // 1. 计划类型明确为 PAYG (PlanType.Payg)
                                // 2. 或升级状态明确为 UPGRADED (UpgradeState.Upgraded)
                                // 3. 或存在明确的计划升级时间点 timePlanUpgrade
                                // ⚠️ 严禁检查支付卡(paymentOptions/isIntentToPay)：甲骨文所有免费号注册时都必须绑卡验资，绑卡绝非升级凭证！
                                boolean isPaygItem = "PAYG".equalsIgnoreCase(planTypeVal)
                                        || "UPGRADED".equalsIgnoreCase(upgradeStateVal)
                                        || (timePlanUpgrade != null);

                                if (isPaygItem) {
                                    foundPayg = true;
                                    paygReason = "OSP官方订阅网关权威确认: 升级号 (PlanType: " + planTypeVal 
                                            + ", UpgradeState: " + upgradeStateVal 
                                            + (timePlanUpgrade != null ? ", 升级时间: " + timePlanUpgrade : "") + ")";
                                    break;
                                }

                                if ("FREE_TIER".equalsIgnoreCase(planTypeVal) || "PROMO".equalsIgnoreCase(upgradeStateVal)) {
                                    hasFreeTierRecord = true;
                                    freeReason = "OSP官方订阅网关权威确认: 免费号 (PlanType: " + planTypeVal + ", UpgradeState: " + upgradeStateVal + ")";
                                }
                            }

                            if (foundPayg) {
                                detectedPayg = true;
                                detectionSource = paygReason;
                            } else if (hasFreeTierRecord) {
                                detectedPayg = false;
                                detectionSource = freeReason;
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("OSP 网关检测暂不可用 [" + accountName + "]: " + e.getMessage());
                }
            }

            // =========================================================================
            // 【保底维度 3】: OneSubscription (企业商业订阅合同)
            // 原理: 针对企业级 CSI 合同租户，检查 OneSubscription 服务中的商业定价模型。
            // =========================================================================
            if (detectedPayg == null && homeRegion != null && !homeRegion.isBlank()) {
                try {
                    var subClient = clients.getSubscriptionClientForRegion(homeRegion);
                    var listResp = subClient.listSubscriptions(
                        com.oracle.bmc.onesubscription.requests.ListSubscriptionsRequest.builder()
                            .compartmentId(tenancyId)
                            .build()
                    );
                    var subs = (listResp != null) ? listResp.getItems() : null;
                    if (subs != null && !subs.isEmpty()) {
                        for (var sub : subs) {
                            var subServices = sub.getSubscribedServices();
                            if (subServices != null && !subServices.isEmpty()) {
                                for (var s : subServices) {
                                    if (isOneSubPayg(s.getPricingModel(), s.getIsIntentToPay(), null, s.getPromoType(), null)) {
                                        detectedPayg = true;
                                        detectionSource = "OneSubscription商业定价模型确认 (PricingModel: " + s.getPricingModel() + ")";
                                        break;
                                    }
                                }
                            }
                            if (detectedPayg != null && detectedPayg) break;
                        }
                    }
                } catch (Exception ignored) {}
            }

            // =========================================================================
            // 【保底维度 4】: 运行中的付费实例规格及超额资源 (Running Instance Shapes)
            // 原理: 免费号在 API 上被严格禁止启动非 Always Free 规格（如 E4/E5/Standard3/裸金属等）。
            // 且免费号物理上绝不可能同时运行超过 4 OCPU 或 24GB 内存的 ARM A1 实例。
            // =========================================================================
            if (detectedPayg == null) {
                try {
                    double totalA1Ocpus = 0;
                    double totalA1Memory = 0;
                    for (Map<String, String> inst : OciCloudService.INSTANCE_CACHE.values()) {
                        if (accountName.equals(inst.get("account"))) {
                            String state = inst.get("state");
                            String shape = inst.get("shape");
                            if ("RUNNING".equalsIgnoreCase(state) && shape != null) {
                                String sLower = shape.toLowerCase();
                                if (sLower.contains("micro")) {
                                    continue;
                                }
                                if (sLower.contains("a1")) {
                                    try {
                                        String ocpusStr = inst.get("ocpus");
                                        String memStr = inst.get("memory");
                                        if (ocpusStr != null) totalA1Ocpus += Double.parseDouble(ocpusStr);
                                        if (memStr != null) totalA1Memory += Double.parseDouble(memStr);
                                    } catch (Exception ignored) {}
                                    continue;
                                }
                                if (sLower.contains("standard.e4") || sLower.contains("standard.e5")
                                        || sLower.contains("standard.e3") || sLower.contains("standard3")
                                        || sLower.contains("standard2") || sLower.contains("optimized")
                                        || sLower.contains("denseio") || sLower.startsWith("bm.")
                                        || sLower.contains("bare-metal")) {
                                    detectedPayg = true;
                                    detectionSource = "正在运行企业级付费规格实例 (" + shape + ")";
                                    break;
                                }
                            }
                        }
                    }
                    if (detectedPayg == null && (totalA1Ocpus > 4.0 || totalA1Memory > 24.0)) {
                        detectedPayg = true;
                        detectionSource = "运行中 ARM A1 资源超过免费限额 (" + (long)totalA1Ocpus + "C/" + (long)totalA1Memory + "G)";
                    }
                } catch (Exception ignored) {}
            }

            // =========================================================================
            // 【保底维度 5】: 终审决议与安全兜底
            // =========================================================================
            if (detectedPayg == null) {
                // 账号命名特征保护：若账号名明确包含升级或 PAYG，予以保护
                String lowerName = accountName.toLowerCase();
                if (lowerName.contains("升级") || lowerName.contains("payg") || lowerName.contains("付费")) {
                    detectedPayg = true;
                    detectionSource = "API瞬时受限，根据账户命名特征保护升级号状态";
                } else {
                    detectedPayg = false;
                    detectionSource = "官方订阅无升级记录且无多区域/付费实例，综合评定为免费号";
                }
            }

            // =========================================================================
            // 结果持久化 (全自动落盘并同步至 SQLite)
            // =========================================================================
            boolean isPayg = detectedPayg;
            System.out.println("💳 [PAYG全自动检测] 账号 [" + accountName + "] -> " + (isPayg ? "升级号 (PAYG)" : "免费号 (FreeTier)") + " (探测依据: " + detectionSource + ")");
            savePaygStatus(accountName, tenancyId, isPayg);

        } catch (Exception e) {
            System.err.println("执行账户 PAYG 检测失败 [" + accountName + "]: " + e.getMessage());
        } finally {
            if (clients != null) {
                clients.releaseLowFrequencyClients();
            }
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    private boolean isOneSubPayg(String pricingModel, Boolean isIntentToPay, Boolean isPayg, String promoType, String orderType) {
        if (Boolean.TRUE.equals(isPayg)) {
            return true;
        }
        if (pricingModel != null) {
            String pm = pricingModel.toUpperCase();
            if (pm.contains("PAYG") || pm.contains("PAY_AS_YOU_GO") || pm.contains("COMMITTED") || pm.contains("CORPORATE") || pm.contains("PAID")) {
                return true;
            }
        }
        if (orderType != null) {
            String ot = orderType.toUpperCase();
            if (ot.contains("UPGRADE") || ot.contains("PAID")) {
                return true;
            }
        }
        if (Boolean.TRUE.equals(isIntentToPay)) {
            if (promoType == null || (!promoType.toUpperCase().contains("FREE") && !promoType.toUpperCase().contains("PROMO"))) {
                return true;
            }
        }
        return false;
    }

    public void clearPaygCache() {
        paygCache.clear();
        paygCheckTime.clear();
        purgeContaminatedPaygCache();
        for (String accountName : ociProperties.getAccounts().keySet()) {
            enqueuePaygCheck(accountName);
        }
    }

    private void autoConfigureSubscriptionPolicy(String accountName, OciProperties.Account accConfig, com.oracle.bmc.identity.IdentityClient identityClient) {
        try {
            String tenancyId = accConfig.getTenancy();
            String userId = accConfig.getUser();
            
            // 1. 获取当前 API Key 所属用户加入的所有组
            var memberships = identityClient.listUserGroupMemberships(
                com.oracle.bmc.identity.requests.ListUserGroupMembershipsRequest.builder()
                    .compartmentId(tenancyId)
                    .userId(userId)
                    .build()
            ).getItems();
            
            if (memberships.isEmpty()) {
                System.err.println("用户未加入任何组，无法自动生成组策略。");
                return;
            }
            
            // 获取第一个所在的组 ID 和名称
            String groupId = memberships.get(0).getGroupId();
            var group = identityClient.getGroup(
                com.oracle.bmc.identity.requests.GetGroupRequest.builder()
                    .groupId(groupId)
                    .build()
            ).getGroup();
            
            String groupName = group.getName();
            String policyName = "OCI_Manager_Subscription_Policy";

            // 甲骨文 IAM 写操作（如创建/更新策略）必须在租户的 Home Region 执行
            var clients = clientManager.getOciClients(accountName);
            String homeReg = null;
            try {
                var regionSubs = identityClient.listRegionSubscriptions(
                    com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest.builder()
                        .tenancyId(tenancyId)
                        .build()
                ).getItems();
                for (var rs : regionSubs) {
                    if (Boolean.TRUE.equals(rs.getIsHomeRegion())) {
                        homeReg = rs.getRegionName();
                        break;
                    }
                }
            } catch (Exception ignored) {}

            var targetIdentityClient = (homeReg != null) ? clients.getIdentityClientForRegion(homeReg) : identityClient;

            java.util.List<String> statements = new java.util.ArrayList<>();
            statements.add("Allow group '" + groupName + "' to read subscriptions in tenancy");
            statements.add("Allow group '" + groupName + "' to read subscription in tenancy");
            statements.add("Allow group '" + groupName + "' to inspect subscriptions in tenancy");
            statements.add("Allow group '" + groupName + "' to inspect subscription in tenancy");
            statements.add("Allow group '" + groupName + "' to read subscribed-services in tenancy");
            statements.add("Allow group '" + groupName + "' to inspect subscribed-services in tenancy");
            statements.add("Allow group '" + groupName + "' to read organizations-subscriptions in tenancy");
            statements.add("Allow group '" + groupName + "' to inspect organizations-subscriptions in tenancy");
            statements.add("Allow group '" + groupName + "' to read usage-reports in tenancy");
            statements.add("Allow group '" + groupName + "' to read usage-budgets in tenancy");
            statements.add("Allow group '" + groupName + "' to inspect usage-budgets in tenancy");
            statements.add("Allow group '" + groupName + "' to inspect compartments in tenancy");
            statements.add("Allow group '" + groupName + "' to inspect tenancies in tenancy");

            // 检查策略是否已存在
            var policies = targetIdentityClient.listPolicies(
                com.oracle.bmc.identity.requests.ListPoliciesRequest.builder()
                    .compartmentId(tenancyId)
                    .build()
            ).getItems();

            for (var p : policies) {
                if (policyName.equalsIgnoreCase(p.getName())) {
                    try {
                        targetIdentityClient.updatePolicy(
                            com.oracle.bmc.identity.requests.UpdatePolicyRequest.builder()
                                .policyId(p.getId())
                                .updatePolicyDetails(
                                    com.oracle.bmc.identity.model.UpdatePolicyDetails.builder()
                                        .statements(statements)
                                        .build()
                                )
                                .build()
                        );
                        System.out.println("成功为账户 [" + accountName + "] 的组 [" + groupName + "] 增补更新了订阅与使用量读取策略！");
                    } catch (Exception ignored) {
                        System.out.println("策略已存在，等待 OCI 权限同步...");
                    }
                    return;
                }
            }

            targetIdentityClient.createPolicy(
                com.oracle.bmc.identity.requests.CreatePolicyRequest.builder()
                    .createPolicyDetails(
                        com.oracle.bmc.identity.model.CreatePolicyDetails.builder()
                            .compartmentId(tenancyId)
                            .name(policyName)
                            .description("Auto-generated policy for OCI-Manager to read subscriptions and usage")
                            .statements(statements)
                            .build()
                    )
                    .build()
            );
            System.out.println("成功为账户 [" + accountName + "] 的组 [" + groupName + "] 自动配置了订阅、账单与使用量读取策略！(生效后后续巡检即可自动精准识别)");
        } catch (Exception e) {
            System.err.println("自动配置策略失败 [" + accountName + "]，请确认您具有创建策略的管理权限: " + e.getMessage());
        }
    }

    public String getCachedCurrentMonthBill(String accountName) {
        if (accountName == null) return "¥ 0.00";
        long now = System.currentTimeMillis();
        String currentCached = billCache.get(accountName);
        Long lastTime = billCacheTime.get(accountName);

        // 如果未缓存，先尝试从本地载入
        if (currentCached == null) {
            loadBillsFromDatabase();
            currentCached = billCache.get(accountName);
            lastTime = billCacheTime.get(accountName);
        }

        // 判断是否处于卡死的"自动计算中..."(超过45秒未返回)
        boolean isStuck = "自动计算中...".equals(currentCached) && (lastTime == null || (now - lastTime) > 45_000L);
        // 缓存有效周期为 1 小时
        boolean isExpired = (lastTime == null || (now - lastTime) > 3_600_000L);

        // 仅当从未缓存过数据或状态彻底卡死，才需要展示"自动计算中..."
        if (currentCached == null || isStuck) {
            billCache.put(accountName, "自动计算中...");
            billCacheTime.put(accountName, now);
            triggerAsyncBillFetch(accountName);
            return "自动计算中...";
        }

        // 🌟 核心优化 (Stale-While-Revalidate 机制)：
        // 如果已有历史账单（如 "¥ 15.00"），即便缓存超过1小时，也绝对不擦除为"自动计算中..."！
        // 界面零等待直接展示当前已有数值，由后台在静默中异步抓取并无感换新。
        if (isExpired && !fetchingBillAccounts.contains(accountName)) {
            triggerAsyncBillFetch(accountName);
        }

        return currentCached;
    }

    private void triggerAsyncBillFetch(String accountName) {
        if (!fetchingBillAccounts.add(accountName)) {
            return; // 已有线程正在抓取该账号，防并发风暴
        }

        CompletableFuture.runAsync(() -> {
            ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(OciCloudService.class.getClassLoader());
                try {
                    String bill = fetchCurrentMonthBill(accountName);
                    if (bill != null) {
                        billCache.put(accountName, bill);
                        billCacheTime.put(accountName, System.currentTimeMillis());
                        saveBillsToDatabase();
                    } else {
                        // 失败时：如果原本就没有有效账单（例如处于"自动计算中..."），兜底置为 ¥ 0.00 避免死循环转圈
                        if ("自动计算中...".equals(billCache.get(accountName))) {
                            billCache.put(accountName, "¥ 0.00");
                        }
                        billCacheTime.put(accountName, System.currentTimeMillis());
                    }
                } catch (Throwable t) {
                    System.err.println("异步获取账单失败 [" + accountName + "]: " + t.getMessage());
                    if ("自动计算中...".equals(billCache.get(accountName))) {
                        billCache.put(accountName, "¥ 0.00");
                    }
                    billCacheTime.put(accountName, System.currentTimeMillis());
                }
            } finally {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
                fetchingBillAccounts.remove(accountName);
            }
        });
    }

    /**
     * 🌟 后台错峰批量刷新所有账号账单，并保存
     */
    public void refreshAllBillsInBackground() {
        Set<String> accountNames = ociProperties.getAccounts().keySet();
        if (accountNames.isEmpty()) return;
        System.out.println("🔄 [账单与升级保活] 开始后台静默刷新所有账号账单与 PAYG 状态...");
        int count = 0;
        for (String accName : accountNames) {
            if (count > 0) {
                // 🌟 差异化离散间隔：各账号独立动态微抖动 (2200ms ~ 4000ms)，打散请求时序
                int jitter = 2200 + java.util.concurrent.ThreadLocalRandom.current().nextInt(1800);
                try { Thread.sleep(jitter); } catch (InterruptedException ignored) {}
            }
            count++;

            if (fetchingBillAccounts.add(accName)) {
                ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
                try {
                    Thread.currentThread().setContextClassLoader(OciCloudService.class.getClassLoader());
                    String bill = fetchCurrentMonthBill(accName);
                    if (bill != null) {
                        billCache.put(accName, bill);
                        billCacheTime.put(accName, System.currentTimeMillis());

                    }
                } catch (Exception e) {
                    System.err.println("❌ 后台静默刷新账号 [" + accName + "] 账单失败: " + e.getMessage());
                } finally {
                    Thread.currentThread().setContextClassLoader(originalClassLoader);
                    fetchingBillAccounts.remove(accName);
                }
            }

            // 🌟 定期自动巡检 PAYG 状态 (若距上次检测超过 12 小时，自动触发重检，捕获云端新升级)
            Long lastPaygCheck = paygCheckTime.get(accName);
            if (lastPaygCheck == null || (System.currentTimeMillis() - lastPaygCheck > 43_200_000L)) {
                enqueuePaygCheck(accName);
            }

            // 🌟 注册开户时间自动巡检补全：若账号尚未录入有效注册时间，后台自动调度探测抓取
            OciProperties.Account targetAcc = ociProperties.getAccounts().get(accName);
            if (targetAcc != null && !isValidRegisterTime(targetAcc.getRegisterTime())) {
                triggerAutoFetchRegisterTime(accName);
            }
        }
        saveBillsToDatabase();
        System.out.println("✅ [账单与升级保活] 所有账号账单、PAYG 状态与注册时间后台自动刷新完毕。");
    }

    /**
     * 🌟 月初重置：清空旧月账单并重新在后台计算
     */
    public void handleMonthRollover() {
        System.out.println("🗓️ [账单月初重置] 检测到月初重置，清空上月账单缓存并启动后台重新计算...");
        billCache.clear();
        billCacheTime.clear();
        try {
            systemConfigRepository.deleteById("BILL_CACHE_BILLS");
            systemConfigRepository.deleteById("BILL_CACHE_TIMES");
            String currentMonth = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM"));
            systemConfigRepository.save(new SystemConfig("BILL_CACHE_MONTH", currentMonth));
        } catch (Exception ignored) {}
        CompletableFuture.runAsync(this::refreshAllBillsInBackground);
    }

    public void saveBillsToDatabase() {
        try {
            if (!billCache.isEmpty()) {
                systemConfigRepository.save(new SystemConfig("BILL_CACHE_BILLS", objectMapper.writeValueAsString(billCache)));
                systemConfigRepository.save(new SystemConfig("BILL_CACHE_TIMES", objectMapper.writeValueAsString(billCacheTime)));
                String currentMonth = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM"));
                systemConfigRepository.save(new SystemConfig("BILL_CACHE_MONTH", currentMonth));
            }
        } catch (Exception e) {
            System.err.println("保存账单缓存到 SQLite 失败: " + e.getMessage());
        }
    }

    public void loadBillsFromDatabase() {
        try {
            String currentMonth = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM"));
            var monthCfg = systemConfigRepository.findById("BILL_CACHE_MONTH");
            String cachedMonth = monthCfg.map(SystemConfig::getConfigValue).orElse(null);

            // 如果跨月了，旧月份账单不载入，自动重置
            if (cachedMonth != null && !cachedMonth.equals(currentMonth)) {
                System.out.println("🗓️ [账单缓存] 检测到月份已变更 (缓存月: " + cachedMonth + ", 当前月: " + currentMonth + ")，重置账单缓存。");
                systemConfigRepository.deleteById("BILL_CACHE_BILLS");
                systemConfigRepository.deleteById("BILL_CACHE_TIMES");
                systemConfigRepository.save(new SystemConfig("BILL_CACHE_MONTH", currentMonth));
                return;
            }

            var billsCfg = systemConfigRepository.findById("BILL_CACHE_BILLS");
            if (billsCfg.isPresent() && billsCfg.get().getConfigValue() != null) {
                Map<String, String> loadedBills = objectMapper.readValue(billsCfg.get().getConfigValue(), new TypeReference<Map<String, String>>(){});
                loadedBills.forEach((k, v) -> {
                    if (v != null && !"自动计算中...".equals(v)) {
                        billCache.put(k, v);
                    }
                });
            }

            var timesCfg = systemConfigRepository.findById("BILL_CACHE_TIMES");
            if (timesCfg.isPresent() && timesCfg.get().getConfigValue() != null) {
                Map<String, Long> loadedTimes = objectMapper.readValue(timesCfg.get().getConfigValue(), new TypeReference<Map<String, Long>>(){});
                billCacheTime.putAll(loadedTimes);
            }
        } catch (Exception e) {
            System.err.println("❌ 从 SQLite 加载账单缓存失败: " + e.getMessage());
        }
    }

    public void clearBillCache() {
        // 清理缓存时间标记，促使下次或后台拉取最新，但不立即将已有数值抹成"自动计算中..."
        billCacheTime.clear();
    }

    private static final Map<String, Double> DEFAULT_EXCHANGE_RATES = new ConcurrentHashMap<>();
    private static final Map<String, Double> DYNAMIC_EXCHANGE_RATES = new ConcurrentHashMap<>();
    private static volatile long lastExchangeRateFetchTime = 0L;

    static {
        DEFAULT_EXCHANGE_RATES.put("CNY", 1.0);
        DEFAULT_EXCHANGE_RATES.put("RMB", 1.0);
        DEFAULT_EXCHANGE_RATES.put("USD", 7.25);
        DEFAULT_EXCHANGE_RATES.put("EUR", 7.85);
        DEFAULT_EXCHANGE_RATES.put("GBP", 9.30);
        DEFAULT_EXCHANGE_RATES.put("JPY", 0.046);
        DEFAULT_EXCHANGE_RATES.put("HKD", 0.93);
        DEFAULT_EXCHANGE_RATES.put("SGD", 5.40);
        DEFAULT_EXCHANGE_RATES.put("AUD", 4.75);
        DEFAULT_EXCHANGE_RATES.put("CAD", 5.30);
        DEFAULT_EXCHANGE_RATES.put("KRW", 0.0053);
        DEFAULT_EXCHANGE_RATES.put("TWD", 0.22);
        DEFAULT_EXCHANGE_RATES.put("BRL", 1.30);
        DEFAULT_EXCHANGE_RATES.put("INR", 0.086);
        DEFAULT_EXCHANGE_RATES.put("NZD", 4.35);
        DEFAULT_EXCHANGE_RATES.put("CHF", 8.10);
        DEFAULT_EXCHANGE_RATES.put("SEK", 0.68);
        DEFAULT_EXCHANGE_RATES.put("NOK", 0.67);
        DEFAULT_EXCHANGE_RATES.put("MXN", 0.40);
        DEFAULT_EXCHANGE_RATES.put("MYR", 1.65);
        DEFAULT_EXCHANGE_RATES.put("THB", 0.20);
        DEFAULT_EXCHANGE_RATES.put("IDR", 0.00045);
        DEFAULT_EXCHANGE_RATES.put("VND", 0.00029);
        DEFAULT_EXCHANGE_RATES.put("AED", 1.97);
        DEFAULT_EXCHANGE_RATES.put("SAR", 1.93);
        DEFAULT_EXCHANGE_RATES.put("TRY", 0.22);
        DEFAULT_EXCHANGE_RATES.put("RUB", 0.080);
    }

    public double getExchangeRateToCny(String currencyCode) {
        if (currencyCode == null || currencyCode.trim().isEmpty()) return 1.0;
        String curr = currencyCode.trim().toUpperCase();
        if ("CNY".equals(curr) || "RMB".equals(curr)) return 1.0;

        refreshExchangeRatesIfExpired();

        if (DYNAMIC_EXCHANGE_RATES.containsKey(curr)) {
            return DYNAMIC_EXCHANGE_RATES.get(curr);
        }
        return DEFAULT_EXCHANGE_RATES.getOrDefault(curr, 7.25);
    }

    // 🚀 单飞刷新守卫：确保同一时刻只有一个线程去拉取汇率，其余线程不再排队等待网络。
    private final java.util.concurrent.atomic.AtomicBoolean exchangeRateRefreshing = new java.util.concurrent.atomic.AtomicBoolean(false);

    // 🚀 【锁内网络 I/O 修复】原方法为 synchronized，会在持有实例锁期间执行阻塞 HTTP，
    // 导致所有账单展示线程串行阻塞。现改为无锁 + CAS 单飞，并复用共享 HTTP_CLIENT。
    private void refreshExchangeRatesIfExpired() {
        long now = System.currentTimeMillis();
        if (now - lastExchangeRateFetchTime < 43200000L && !DYNAMIC_EXCHANGE_RATES.isEmpty()) {
            return;
        }
        // 抢不到刷新权的线程直接返回，使用现有/预设汇率，绝不阻塞在网络上。
        if (!exchangeRateRefreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://open.er-api.com/v6/latest/CNY"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = OciCloudService.HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body() != null) {
                var jsonNode = objectMapper.readTree(response.body());
                if (jsonNode.has("rates")) {
                    var rates = jsonNode.get("rates");
                    rates.fieldNames().forEachRemaining(code -> {
                        double rateToCny = rates.get(code).asDouble();
                        if (rateToCny > 0) {
                            DYNAMIC_EXCHANGE_RATES.put(code.toUpperCase(), 1.0 / rateToCny);
                        }
                    });
                    lastExchangeRateFetchTime = now;
                    System.out.println("成功更新全球最新汇率数据 (以人民币 CNY 结算)！");
                }
            }
        } catch (Exception e) {
            System.err.println("尝试获取实时汇率失败，将使用预设汇率表: " + e.getMessage());
            lastExchangeRateFetchTime = now;
        } finally {
            exchangeRateRefreshing.set(false);
        }
    }

    public String fetchCurrentMonthBill(String accountName) {
        OciClientWrapper clients = null;
        try {
            OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
            if (accConfig == null) return "¥ 0.00";
            clients = clientManager.getOciClients(accountName);
            String tenancyId = accConfig.getTenancy();

            // 查找主区域 (Home Region)
            String homeRegion = null;
            try {
                var regionSubs = clients.getIdentity().listRegionSubscriptions(
                    com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest.builder()
                        .tenancyId(tenancyId)
                        .build()
                ).getItems();
                for (var rs : regionSubs) {
                    if (Boolean.TRUE.equals(rs.getIsHomeRegion())) {
                        homeRegion = rs.getRegionName();
                        break;
                    }
                }
            } catch (Exception ignored) {}

            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            ZonedDateTime firstDayOfMonth = now.withDayOfMonth(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
            ZonedDateTime firstDayOfNextMonth = firstDayOfMonth.plusMonths(1);

            // 🌟 核心修复：OCI Usage API 要求 Daily 粒度的起始和结束时间必须严格对齐 00:00:00 UTC（无时分秒偏差）。
            // 且结束时间为开区间。设置范围为当月 1 号 00:00 至次月 1 号 00:00，API 将完整精准返回当月所有已产生的历史成本。
            RequestSummarizedUsagesDetails details = RequestSummarizedUsagesDetails.builder()
                .tenantId(tenancyId)
                .timeUsageStarted(Date.from(firstDayOfMonth.toInstant()))
                .timeUsageEnded(Date.from(firstDayOfNextMonth.toInstant()))
                .granularity(RequestSummarizedUsagesDetails.Granularity.Daily)
                .queryType(RequestSummarizedUsagesDetails.QueryType.Cost)
                .build();

            List<UsageSummary> allItems = queryUsageItemsWithRetryAndPagination(clients, homeRegion, details, accountName, accConfig);
            if (allItems == null) {
                System.err.println("Usage API 查询异常 [" + accountName + "]，将保留旧账单数据");
                return null;
            }
            if (allItems.isEmpty()) {
                return "¥ 0.00";
            }

            Map<String, Double> costByCurrency = new HashMap<>();
            for (UsageSummary item : allItems) {
                // 排除预测数据，仅汇总实际已产生的成本
                if (Boolean.TRUE.equals(item.getIsForecast())) {
                    continue;
                }
                if (item.getComputedAmount() != null) {
                    double amt = item.getComputedAmount().doubleValue();
                    if (amt > 0) {
                        String curr = (item.getCurrency() != null && !item.getCurrency().trim().isEmpty())
                                ? item.getCurrency().trim().toUpperCase()
                                : "USD";
                        costByCurrency.put(curr, costByCurrency.getOrDefault(curr, 0.0) + amt);
                    }
                }
            }

            if (costByCurrency.isEmpty()) {
                return "¥ 0.00";
            }

            double totalRmb = 0.0;
            for (Map.Entry<String, Double> entry : costByCurrency.entrySet()) {
                String curr = entry.getKey();
                double amt = entry.getValue();
                double rate = getExchangeRateToCny(curr);
                totalRmb += amt * rate;
            }

            return String.format(Locale.US, "¥ %.2f", totalRmb);
        } catch (Throwable e) {
            System.err.println("获取账单失败 [" + accountName + "]: " + e.getMessage());
            return null;
        } finally {
            if (clients != null) {
                clients.releaseLowFrequencyClients();
            }
        }
    }

    private List<UsageSummary> queryUsageItemsWithRetryAndPagination(
            OciClientWrapper clients,
            String homeRegion,
            RequestSummarizedUsagesDetails details,
            String accountName,
            OciProperties.Account accConfig) {
        
        List<com.oracle.bmc.usageapi.UsageapiClient> clientsToTry = new ArrayList<>();

        // 1. 主区域优先：Usage API 为租户级服务，Home Region 是最权威可信的查询节点
        if (homeRegion != null && !homeRegion.trim().isEmpty()) {
            try {
                var homeUsageClient = clients.getUsageapiClientForRegion(homeRegion);
                if (homeUsageClient != null) {
                    clientsToTry.add(homeUsageClient);
                }
            } catch (Exception ignored) {}
        }

        // 2. 默认区域作为备用
        try {
            var defaultUsageClient = clients.getUsageapi();
            if (defaultUsageClient != null && !clientsToTry.contains(defaultUsageClient)) {
                clientsToTry.add(defaultUsageClient);
            }
        } catch (Exception ignored) {}

        for (var usageClient : clientsToTry) {
            try {
                List<UsageSummary> resultItems = new ArrayList<>();
                String nextPage = null;
                do {
                    var request = RequestSummarizedUsagesRequest.builder()
                            .requestSummarizedUsagesDetails(details)
                            .page(nextPage)
                            .build();

                    var response = usageClient.requestSummarizedUsages(request);
                    if (response != null && response.getUsageAggregation() != null && response.getUsageAggregation().getItems() != null) {
                        resultItems.addAll(response.getUsageAggregation().getItems());
                    }
                    nextPage = (response != null) ? response.getOpcNextPage() : null;
                } while (nextPage != null && !nextPage.trim().isEmpty());

                return resultItems;
            } catch (com.oracle.bmc.model.BmcException bmcEx) {
                if (bmcEx.getStatusCode() == 404 || bmcEx.getStatusCode() == 401 || (bmcEx.getMessage() != null && bmcEx.getMessage().contains("NotAuthorizedOrNotFound"))) {
                    System.err.println("Usage API 权限不足或未找到 [" + accountName + "]，尝试配置策略: " + bmcEx.getMessage());
                    autoConfigureSubscriptionPolicy(accountName, accConfig, clients.getIdentity());
                } else {
                    System.err.println("Usage API 查询异常 [" + accountName + "]: " + bmcEx.getMessage());
                }
            } catch (Throwable ex) {
                System.err.println("Usage API 调用异常 [" + accountName + "]: " + ex.getMessage());
            }
        }

        return null;
    }

    /**
     * 🌟 自动通过 Identity API 获取 Tenancy（租户）的真实注册创建时间
     * 采用多级容错与 Home Region 自动路由机制：
     * 1. 优先获取租户的 Home Region，因为 OCI Identity 根区段查询只在主区域权威生效
     * 2. 尝试查询根区段 (Root Compartment) 的 timeCreated
     * 3. 兜底策略：若权限受限无权读取 Compartment，则查询当前 API 用户 (User OCID) 的 timeCreated（任何有效凭据皆有权查询自己）
     * 4. 回退机制：若 Home Region 查询失败，回退到配置区域再次尝试
     */
    public String getTenancyCreateTime(String accountName) {
        OciClientWrapper clients = null;
        try {
            OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
            if (accConfig == null) return null;

            clients = clientManager.getOciClients(accountName);
            String tenancyId = accConfig.getTenancy();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

            // 1. 探测 Home Region (listRegionSubscriptions 允许在任何订阅区域调用)
            String homeRegion = null;
            try {
                var regionSubs = clients.getIdentity().listRegionSubscriptions(
                    ListRegionSubscriptionsRequest.builder().tenancyId(tenancyId).build()
                ).getItems();
                if (regionSubs != null) {
                    for (var rs : regionSubs) {
                        if (Boolean.TRUE.equals(rs.getIsHomeRegion())) {
                            homeRegion = rs.getRegionName();
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}

            List<IdentityClient> candidates = new ArrayList<>();
            if (homeRegion != null) {
                try {
                    candidates.add(clients.getIdentityClientForRegion(homeRegion));
                } catch (Exception ignored) {}
            }
            candidates.add(clients.getIdentity());

            // 尝试 0: OSP 网关官方订阅开始时间 (参考 oci-helper / oci-start，最精准权威的开户日期)
            if (homeRegion != null && !homeRegion.isBlank()) {
                try {
                    var ospClient = clients.getOspSubscriptionClientForRegion(homeRegion);
                    var ospReq = ListSubscriptionsRequest.builder()
                        .compartmentId(tenancyId)
                        .ospHomeRegion(homeRegion)
                        .build();
                    var ospResp = ospClient.listSubscriptions(ospReq);
                    if (ospResp != null && ospResp.getSubscriptionCollection() != null) {
                        var items = ospResp.getSubscriptionCollection().getItems();
                        if (items != null && !items.isEmpty()) {
                            Date t = items.get(0).getTimeStart();
                            if (t != null) {
                                return sdf.format(t);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 2. 依次在候选 Identity 客户端中尝试：根区段创建时间 -> API 用户创建时间 -> 用户列表最早时间
            for (IdentityClient idClient : candidates) {
                if (idClient == null) continue;

                // 尝试 A：根区段创建时间 (最精准的 Tenancy 注册时间)
                try {
                    var compResp = idClient.getCompartment(
                        GetCompartmentRequest.builder().compartmentId(tenancyId).build()
                    );
                    if (compResp != null && compResp.getCompartment() != null) {
                        Date timeCreated = compResp.getCompartment().getTimeCreated();
                        if (timeCreated != null) {
                            return sdf.format(timeCreated);
                        }
                    }
                } catch (Exception ignored) {}

                // 尝试 B：当前 API 用户的创建时间 (针对子用户或 Compartment 策略受限的情况)
                if (accConfig.getUser() != null && !accConfig.getUser().isBlank()) {
                    try {
                        var userResp = idClient.getUser(
                            GetUserRequest.builder().userId(accConfig.getUser()).build()
                        );
                        if (userResp != null && userResp.getUser() != null) {
                            Date timeCreated = userResp.getUser().getTimeCreated();
                            if (timeCreated != null) {
                                return sdf.format(timeCreated);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // 尝试 C：查询租户内所有用户，取最早创建的主管理员用户创建时间
                try {
                    var listUserResp = idClient.listUsers(
                        ListUsersRequest.builder().compartmentId(tenancyId).limit(50).build()
                    );
                    if (listUserResp != null && listUserResp.getItems() != null && !listUserResp.getItems().isEmpty()) {
                        Date earliest = null;
                        for (var u : listUserResp.getItems()) {
                            Date t = u.getTimeCreated();
                            if (t != null && (earliest == null || t.before(earliest))) {
                                earliest = t;
                            }
                        }
                        if (earliest != null) {
                            return sdf.format(earliest);
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 尝试 D：从当前账号的计算实例中查询最早创建的机器时间作为保底参考
            try {
                var compClient = clients.getCompute();
                var instList = compClient.listInstances(
                    ListInstancesRequest.builder().compartmentId(tenancyId).limit(50).build()
                );
                if (instList != null && instList.getItems() != null && !instList.getItems().isEmpty()) {
                    Date earliest = null;
                    for (var inst : instList.getItems()) {
                        Date t = inst.getTimeCreated();
                        if (t != null && (earliest == null || t.before(earliest))) {
                            earliest = t;
                        }
                    }
                    if (earliest != null) {
                        return sdf.format(earliest);
                    }
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            System.err.println("API自动获取注册时间失败 [" + accountName + "]: " + e.getMessage());
        } finally {
            if (clients != null) {
                clients.releaseLowFrequencyClients();
            }
        }
        return null;
    }

    public void triggerAutoFetchRegisterTime(String accountName) {
        if (accountName == null || accountName.isBlank()) return;
        if (ACTIVE_REG_FETCH_TASKS.add(accountName)) {
            CompletableFuture.runAsync(() -> {
                try {
                    int attempt = 0;
                    int maxAttempts = 3;
                    while (attempt < maxAttempts) {
                        attempt++;
                        OciProperties.Account targetAcc = ociProperties.getAccounts().get(accountName);
                        if (targetAcc == null) break;
                        if (isValidRegisterTime(targetAcc.getRegisterTime())) {
                            break; // 已成功获取并持久化
                        }

                        try {
                            String apiTime = getTenancyCreateTime(accountName);
                            if (isValidRegisterTime(apiTime)) {
                                targetAcc.setRegisterTime(apiTime);
                                if (accountYamlService != null) {
                                    accountYamlService.persistRegisterTimeToYaml(accountName, apiTime);
                                }
                                System.out.println("✅ [自动抓取] 成功获取并持久化账号 [" + accountName + "] 注册时间: " + apiTime);
                                break;
                            }
                        } catch (Exception e) {
                            System.err.println("⚠️ [自动抓取] 获取账号 [" + accountName + "] 注册时间失败 (第 " + attempt + " 次): " + e.getMessage());
                        }

                        if (attempt < maxAttempts) {
                            try {
                                Thread.sleep(2000L);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }

                    // 🌟 3次尝试后依然未能获取到，将其置为"未记录"，防止前端页面无休止快速轮询卡死
                    OciProperties.Account finalAcc = ociProperties.getAccounts().get(accountName);
                    if (finalAcc != null && !isValidRegisterTime(finalAcc.getRegisterTime())) {
                        finalAcc.setRegisterTime("未记录");
                    }
                } finally {
                    ACTIVE_REG_FETCH_TASKS.remove(accountName);
                }
            });
        }
    }

    public double fetchTrafficInGb(String accountName) {
        try {
            OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
            if (accConfig == null && accountName != null) {
                String clean = accountName.replaceAll("[\"'\\s\\[\\]\\r\\n]", "");
                for (Map.Entry<String, OciProperties.Account> e : ociProperties.getAccounts().entrySet()) {
                    if (e.getKey().replaceAll("[\"'\\s\\[\\]\\r\\n]", "").equals(clean)) {
                        accConfig = e.getValue();
                        accountName = e.getKey();
                        break;
                    }
                }
            }
            if (accConfig == null) {
                System.err.println("获取流量失败 [" + accountName + "]: 账号配置不存在");
                return -1.0;
            }

            var clients = clientManager.getOciClients(accountName);
            java.util.List<String> regions = clientManager.getSubscribedRegions(accountName);
            if (regions == null || regions.isEmpty()) {
                System.err.println("获取流量失败 [" + accountName + "]: 订阅区域列表为空");
                return -1.0;
            }

            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            ZonedDateTime start = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);

            double totalBytes = 0;
            boolean anyRegionSuccess = false;
            boolean anyRegionFailure = false;

            for (String region : regions) {
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        var monitoringClient = clients.getMonitoringClientForRegion(region);
                        var response = monitoringClient.summarizeMetricsData(SummarizeMetricsDataRequest.builder()
                                .compartmentId(accConfig.getTenancy())
                                .summarizeMetricsDataDetails(SummarizeMetricsDataDetails.builder()
                                        .namespace("oci_vcn")
                                        .query("VnicToNetworkBytes[1h].sum()")
                                        .startTime(Date.from(start.toInstant()))
                                        .endTime(Date.from(now.toInstant())).build())
                                .compartmentIdInSubtree(true).build());

                        for (var item : response.getItems()) {
                            for (var dp : item.getAggregatedDatapoints()) {
                                totalBytes += dp.getValue();
                            }
                        }
                        anyRegionSuccess = true;
                        break;
                    } catch (Exception ex) {
                        if (attempt < 2) {
                            try { Thread.sleep(2000L * (attempt + 1)); } catch (InterruptedException ignored) {}
                        } else {
                            System.err.println("获取流量失败 [" + accountName + " - " + region + "]: " + ex.getMessage());
                            anyRegionFailure = true;
                        }
                    }
                }
            }

            // 若所有区域均查询失败，或者有区域失败且总字节为 0，不能视作真实 0 字节，应返回 -1 标示失败
            if (!anyRegionSuccess || (anyRegionFailure && totalBytes <= 0)) {
                return -1.0;
            }

            return totalBytes / (1024.0 * 1024.0 * 1024.0);
        } catch (Exception e) {
            System.err.println("获取总流量失败 [" + accountName + "]: " + e.getMessage());
            return -1.0;
        }
    }
}
