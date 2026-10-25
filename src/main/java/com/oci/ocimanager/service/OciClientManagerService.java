package com.oci.ocimanager.service;

import com.oci.ocimanager.config.OciProperties;
import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.auth.SimplePrivateKeySupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 客户端与认证域服务：负责 OCI 认证 Provider 与 SDK 客户端包装器的创建、缓存与生命周期。
 * 结构重构自 OciCloudService（门面保留原对外方法并委托到此处）；
 * 共享静态缓存仍物理存放于 OciCloudService。
 */
@Service
public class OciClientManagerService {

    @Autowired
    private OciProperties ociProperties;

    @Autowired
    private OciClientFingerprintService fingerprintService;

    public synchronized SimpleAuthenticationDetailsProvider getProvider(String accountName, OciProperties.Account account) throws Exception {
        if (account == null) {
            throw new IllegalArgumentException("该账号的对象结构完全为空，请检查 YAML 是否存在语法或缩进错误！");
        }

        if (OciCloudService.PROVIDER_CACHE.containsKey(accountName)) {
            return OciCloudService.PROVIDER_CACHE.get(accountName);
        }

        if (account.getKeyFile() == null) {
            throw new IllegalArgumentException("未配置私钥路径 (缺少 key-file 字段)");
        }
        if (account.getTenancy() == null) {
            throw new IllegalArgumentException("未配置租户 ID (缺少 tenancy 字段)");
        }
        if (account.getUser() == null) {
            throw new IllegalArgumentException("未配置用户 ID (缺少 user 字段)");
        }
        if (account.getFingerprint() == null) {
            throw new IllegalArgumentException("未配置指纹 (缺少 fingerprint 字段)");
        }
        if (account.getRegion() == null) {
            throw new IllegalArgumentException("未配置区域 (缺少 region 字段)");
        }

        Path keyPath = Paths.get(account.getKeyFile());
        if (!Files.exists(keyPath)) {
            throw new IllegalArgumentException("私钥文件不存在: " + account.getKeyFile());
        }
        SimpleAuthenticationDetailsProvider provider = SimpleAuthenticationDetailsProvider.builder()
                .tenantId(account.getTenancy())
                .userId(account.getUser())
                .fingerprint(account.getFingerprint())
                .privateKeySupplier(new SimplePrivateKeySupplier(account.getKeyFile()))
                .region(com.oracle.bmc.Region.valueOf(account.getRegion()))
                .build();

        OciCloudService.PROVIDER_CACHE.put(accountName, provider);
        return provider;
    }

    public synchronized OciClientWrapper getOciClients(String accountName) throws Exception {
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        if (accConfig == null && accountName != null) {
            String clean = accountName.replaceAll("[\"'\\s\\[\\]\\r\\n]", "");
            for (Map.Entry<String, OciProperties.Account> e : ociProperties.getAccounts().entrySet()) {
                if (e.getKey().replaceAll("[\"'\\s\\[\\]\\r\\n]", "").equals(clean)) {
                    accConfig = e.getValue();
                    break;
                }
            }
        }
        if (accConfig == null) {
            throw new IllegalArgumentException("账号配置不存在: " + accountName);
        }

        if (OciCloudService.CLIENT_CACHE.containsKey(accountName)) {
            return OciCloudService.CLIENT_CACHE.get(accountName);
        }

        var provider = getProvider(accountName, accConfig);
        var fingerprint = fingerprintService.getFingerprint(accountName, accConfig);

        // 🔒 各账号使用专属的离散化 ClientConfiguration（差异化超时时间、并发数，同时锁定 MaxAttempts=1 杜绝 429 级联雪崩）
        ClientConfiguration accountClientConfig = ClientConfiguration.builder()
                .connectionTimeoutMillis(fingerprint.connectTimeoutMillis())
                .readTimeoutMillis(fingerprint.readTimeoutMillis())
                .maxAsyncThreads(fingerprint.maxAsyncThreads())
                .retryConfiguration(com.oracle.bmc.retrier.RetryConfiguration.builder()
                        .terminationStrategy(new com.oracle.bmc.waiter.MaxAttemptsTerminationStrategy(1))
                        .build())
                .build();

        OciClientWrapper wrapper = new OciClientWrapper(accountName, provider, accountClientConfig, fingerprint, accConfig);
        OciCloudService.CLIENT_CACHE.put(accountName, wrapper);
        return wrapper;
    }

    public OciClientFingerprintService.ClientFingerprint getClientFingerprint(String accountName) {
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        if (accConfig == null && accountName != null) {
            String clean = accountName.replaceAll("[\"'\\s\\[\\]\\r\\n]", "");
            for (Map.Entry<String, OciProperties.Account> e : ociProperties.getAccounts().entrySet()) {
                if (e.getKey().replaceAll("[\"'\\s\\[\\]\\r\\n]", "").equals(clean)) {
                    accConfig = e.getValue();
                    break;
                }
            }
        }
        return fingerprintService.getFingerprint(accountName, accConfig);
    }

    public int getAccountPhaseOffsetMs(String accountName) {
        var fp = getClientFingerprint(accountName);
        return (fp != null) ? fp.phaseOffsetMs() : 0;
    }

    public void clearProviderCache() {
        fingerprintService.clearCache(null);
        OciCloudService.PROVIDER_CACHE.clear();
        List<OciClientWrapper> wrappers;
        synchronized (OciCloudService.CLIENT_CACHE) {
            wrappers = new ArrayList<>(OciCloudService.CLIENT_CACHE.values());
            OciCloudService.CLIENT_CACHE.clear();
        }
        for (OciClientWrapper wrapper : wrappers) {
            if (wrapper != null) {
                wrapper.closeAll();
            }
        }
        OciCloudService.AD_LIST_CACHE.clear();
        OciCloudService.IMAGE_CACHE.clear();
        OciCloudService.SUBNET_CACHE.clear();
        OciCloudService.SUBSCRIBED_REGIONS_CACHE.clear();
    }

    public void clearClientCacheForAccount(String accountName) {
        if (accountName == null) return;
        fingerprintService.clearCache(accountName);
        OciCloudService.PROVIDER_CACHE.remove(accountName);
        OciClientWrapper wrapper = OciCloudService.CLIENT_CACHE.remove(accountName);
        if (wrapper != null) {
            try {
                wrapper.closeAll();
            } catch (Exception ignored) {}
        }
    }

    public void clearRegionsCache() {
        OciCloudService.SUBSCRIBED_REGIONS_CACHE.clear();
    }

    public java.util.List<String> getSubscribedRegions(String accountName) {
        if (OciCloudService.SUBSCRIBED_REGIONS_CACHE.containsKey(accountName)) {
            return OciCloudService.SUBSCRIBED_REGIONS_CACHE.get(accountName);
        }
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        if (accConfig == null && accountName != null) {
            String clean = accountName.replaceAll("[\"'\\s\\[\\]\\r\\n]", "");
            for (Map.Entry<String, OciProperties.Account> e : ociProperties.getAccounts().entrySet()) {
                if (e.getKey().replaceAll("[\"'\\s\\[\\]\\r\\n]", "").equals(clean)) {
                    accConfig = e.getValue();
                    break;
                }
            }
        }
        if (accConfig == null) return new java.util.ArrayList<>();

        java.util.List<String> regions = new java.util.ArrayList<>();
        try {
            var clients = getOciClients(accountName);
            String tenancyId = accConfig.getTenancy();
            var regionSubs = clients.getIdentity().listRegionSubscriptions(
                com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest.builder()
                    .tenancyId(tenancyId)
                    .build()
            ).getItems();
            for (var rs : regionSubs) {
                if (rs.getRegionName() != null && !rs.getRegionName().isEmpty()) {
                    regions.add(rs.getRegionName());
                }
            }
        } catch (Exception e) {
            System.err.println("获取账户 [" + accountName + "] 订阅区域失败: " + e.getMessage());
        }
        if (regions.isEmpty() && accConfig.getRegion() != null) {
            regions.add(accConfig.getRegion());
        }
        OciCloudService.SUBSCRIBED_REGIONS_CACHE.put(accountName, regions);
        return regions;
    }

    public Map<String, Object> getAccountRegionInfo(String accountName) {
        Map<String, Object> res = new HashMap<>();
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        if (accConfig == null && accountName != null) {
            String clean = accountName.replaceAll("[\"'\\s\\[\\]\\r\\n]", "");
            for (Map.Entry<String, OciProperties.Account> e : ociProperties.getAccounts().entrySet()) {
                if (e.getKey().replaceAll("[\"'\\s\\[\\]\\r\\n]", "").equals(clean)) {
                    accConfig = e.getValue();
                    break;
                }
            }
        }
        if (accConfig == null) {
            res.put("success", false);
            res.put("error", "未找到该账号配置: " + accountName);
            return res;
        }

        try {
            var clients = getOciClients(accountName);
            String tenancyId = accConfig.getTenancy();

            List<Map<String, Object>> subscribedList = new ArrayList<>();
            String homeRegion = null;
            try {
                var regionSubs = clients.getIdentity().listRegionSubscriptions(
                    com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest.builder()
                        .tenancyId(tenancyId)
                        .build()
                ).getItems();
                if (regionSubs != null) {
                    for (var rs : regionSubs) {
                        Map<String, Object> subMap = new HashMap<>();
                        subMap.put("name", rs.getRegionName());
                        subMap.put("key", rs.getRegionKey());
                        subMap.put("status", rs.getStatus() != null ? rs.getStatus().getValue() : "READY");
                        subMap.put("isHome", Boolean.TRUE.equals(rs.getIsHomeRegion()));
                        if (Boolean.TRUE.equals(rs.getIsHomeRegion())) {
                            homeRegion = rs.getRegionName();
                        }
                        subscribedList.add(subMap);
                    }
                }
            } catch (Exception e) {
                System.err.println("获取已订阅区域失败: " + e.getMessage());
            }

            if (homeRegion == null || homeRegion.isBlank()) {
                homeRegion = accConfig.getRegion();
            }

            Set<String> subscribedNames = new HashSet<>();
            for (var sub : subscribedList) {
                if (sub.get("name") != null) subscribedNames.add(sub.get("name").toString().toLowerCase());
                if (sub.get("key") != null) subscribedNames.add(sub.get("key").toString().toLowerCase());
            }
            if (homeRegion != null) {
                subscribedNames.add(homeRegion.toLowerCase());
            }

            List<Map<String, Object>> allRegions = new ArrayList<>();
            try {
                var ociRegions = clients.getIdentity().listRegions(
                    com.oracle.bmc.identity.requests.ListRegionsRequest.builder().build()
                ).getItems();
                if (ociRegions != null) {
                    for (var r : ociRegions) {
                        Map<String, Object> rMap = new HashMap<>();
                        rMap.put("name", r.getName());
                        rMap.put("key", r.getKey());
                        boolean isSub = subscribedNames.contains(r.getName().toLowerCase()) ||
                                       (r.getKey() != null && subscribedNames.contains(r.getKey().toLowerCase()));
                        rMap.put("isSubscribed", isSub);
                        rMap.put("isHome", r.getName().equalsIgnoreCase(homeRegion));
                        allRegions.add(rMap);
                    }
                }
            } catch (Exception e) {
                System.err.println("调用 listRegions 异常，使用 SDK 预设区域: " + e.getMessage());
                for (com.oracle.bmc.Region r : com.oracle.bmc.Region.values()) {
                    if (r.getRegionId() == null) continue;
                    Map<String, Object> rMap = new HashMap<>();
                    rMap.put("name", r.getRegionId());
                    rMap.put("key", r.getRegionCode());
                    boolean isSub = subscribedNames.contains(r.getRegionId().toLowerCase()) ||
                                   (r.getRegionCode() != null && subscribedNames.contains(r.getRegionCode().toLowerCase()));
                    rMap.put("isSubscribed", isSub);
                    rMap.put("isHome", r.getRegionId().equalsIgnoreCase(homeRegion));
                    allRegions.add(rMap);
                }
            }

            allRegions.sort((a, b) -> {
                boolean aHome = Boolean.TRUE.equals(a.get("isHome"));
                boolean bHome = Boolean.TRUE.equals(b.get("isHome"));
                if (aHome != bHome) return aHome ? -1 : 1;
                boolean aSub = Boolean.TRUE.equals(a.get("isSubscribed"));
                boolean bSub = Boolean.TRUE.equals(b.get("isSubscribed"));
                if (aSub != bSub) return aSub ? -1 : 1;
                return String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name")));
            });

            res.put("success", true);
            res.put("account", accountName);
            res.put("homeRegion", homeRegion);
            res.put("tenancyId", tenancyId);
            res.put("subscribedRegions", subscribedList);
            res.put("allRegions", allRegions);
            return res;
        } catch (Exception e) {
            res.put("success", false);
            res.put("error", "获取账号区域信息失败: " + e.getMessage());
            return res;
        }
    }

    public Map<String, Object> subscribeRegion(String accountName, String targetRegion) {
        return subscribeRegion(accountName, targetRegion, null);
    }

    public Map<String, Object> subscribeRegion(String accountName, String targetRegion, String hintHomeRegion) {
        Map<String, Object> res = new HashMap<>();
        if (targetRegion == null || targetRegion.trim().isEmpty()) {
            res.put("success", false);
            res.put("error", "目标区域标识不能为空");
            return res;
        }
        String cleanTarget = targetRegion.replaceAll("[\"'\\s\\[\\]\\r\\n]", "").trim();

        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        if (accConfig == null && accountName != null) {
            String clean = accountName.replaceAll("[\"'\\s\\[\\]\\r\\n]", "");
            for (Map.Entry<String, OciProperties.Account> e : ociProperties.getAccounts().entrySet()) {
                if (e.getKey().replaceAll("[\"'\\s\\[\\]\\r\\n]", "").equals(clean)) {
                    accConfig = e.getValue();
                    break;
                }
            }
        }
        if (accConfig == null) {
            res.put("success", false);
            res.put("error", "未找到该账号配置: " + accountName);
            return res;
        }

        String regionKey = null;
        String canonicalName = cleanTarget;
        String homeRegion = (hintHomeRegion != null && !hintHomeRegion.isBlank()) ? hintHomeRegion.trim() : null;

        try {
            var clients = getOciClients(accountName);
            String tenancyId = accConfig.getTenancy();

            // 1. 获取当前 Home Region 与已订阅列表，避免重复订阅 (官方要求：区域订阅请求必须发送至 Home Region)
            List<com.oracle.bmc.identity.model.RegionSubscription> regionSubs = null;
            try {
                regionSubs = clients.getIdentity().listRegionSubscriptions(
                    com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest.builder()
                        .tenancyId(tenancyId)
                        .build()
                ).getItems();
                if (regionSubs != null) {
                    for (var rs : regionSubs) {
                        if (Boolean.TRUE.equals(rs.getIsHomeRegion()) && rs.getRegionName() != null) {
                            homeRegion = rs.getRegionName();
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("调用 listRegionSubscriptions 异常: " + e.getMessage());
            }

            // 若尚未探测到 homeRegion，尝试直接查询租户详情获取权威 Home Region Key
            if (homeRegion == null || homeRegion.isBlank()) {
                try {
                    var tenancyResp = clients.getIdentity().getTenancy(
                        com.oracle.bmc.identity.requests.GetTenancyRequest.builder()
                            .tenancyId(tenancyId)
                            .build()
                    );
                    if (tenancyResp != null && tenancyResp.getTenancy() != null) {
                        String homeKey = tenancyResp.getTenancy().getHomeRegionKey();
                        if (homeKey != null && !homeKey.isBlank()) {
                            try {
                                com.oracle.bmc.Region hr = com.oracle.bmc.Region.fromRegionCodeOrId(homeKey);
                                if (hr != null && hr.getRegionId() != null) {
                                    homeRegion = hr.getRegionId();
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception e) {
                    System.err.println("查询 Tenancy homeRegionKey 异常: " + e.getMessage());
                }
            }

            // 最终兜底：使用账号预设区域
            if (homeRegion == null || homeRegion.isBlank()) {
                homeRegion = accConfig.getRegion();
            }

            // 2. 解析目标区域的权威 Region Key (大写如 SIN, NRT, IAD) 与标准 Region ID
            // ⚠️ 极其关键：甲骨文 CreateRegionSubscriptionDetails 的 regionKey 是大小写严格敏感的，且要求大写 3 位 code（如 SIN/IAD），传小写会导致 404 EntityNotFound！
            try {
                var ociRegions = clients.getIdentity().listRegions(
                    com.oracle.bmc.identity.requests.ListRegionsRequest.builder().build()
                ).getItems();
                if (ociRegions != null) {
                    for (var reg : ociRegions) {
                        if (cleanTarget.equalsIgnoreCase(reg.getName()) || cleanTarget.equalsIgnoreCase(reg.getKey())) {
                            regionKey = reg.getKey();
                            canonicalName = reg.getName();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("调用 listRegions 异常: " + e.getMessage());
            }

            if (regionKey == null) {
                try {
                    com.oracle.bmc.Region r = com.oracle.bmc.Region.fromRegionCodeOrId(cleanTarget);
                    if (r != null) {
                        if (r.getRegionCode() != null) {
                            regionKey = r.getRegionCode().trim().toUpperCase();
                        }
                        if (r.getRegionId() != null) {
                            canonicalName = r.getRegionId();
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (regionKey == null) {
                regionKey = cleanTarget.trim().toUpperCase();
            } else {
                regionKey = regionKey.trim().toUpperCase();
            }

            // 3. 避免向初始主区域 (Home Region) 或已开通区域发起重复订阅
            if (homeRegion != null && (cleanTarget.equalsIgnoreCase(homeRegion) || canonicalName.equalsIgnoreCase(homeRegion))) {
                res.put("success", false);
                res.put("error", "区域 [" + homeRegion + "] 为当前账号的初始主区域 (Home Region)，默认已开通，无需重复订阅！");
                return res;
            }

            if (regionSubs != null) {
                for (var rs : regionSubs) {
                    if (cleanTarget.equalsIgnoreCase(rs.getRegionName()) ||
                        cleanTarget.equalsIgnoreCase(rs.getRegionKey()) ||
                        canonicalName.equalsIgnoreCase(rs.getRegionName()) ||
                        regionKey.equalsIgnoreCase(rs.getRegionKey())) {
                        res.put("success", false);
                        res.put("error", "该区域 [" + rs.getRegionName() + "] 当前已处于已订阅或开通中状态，无需重复订阅！");
                        return res;
                    }
                }
            }

            // 4. 在 Home Region Identity 客户端发起区域订阅请求 (加入幂等 opcRetryToken 防重并处理重试)
            var homeIdentityClient = clients.getIdentityClientForRegion(homeRegion);
            String retryToken = UUID.randomUUID().toString();
            var req = com.oracle.bmc.identity.requests.CreateRegionSubscriptionRequest.builder()
                .tenancyId(tenancyId)
                .opcRetryToken(retryToken)
                .createRegionSubscriptionDetails(
                    com.oracle.bmc.identity.model.CreateRegionSubscriptionDetails.builder()
                        .regionKey(regionKey)
                        .build()
                )
                .build();

            com.oracle.bmc.identity.responses.CreateRegionSubscriptionResponse resp = null;
            try {
                resp = homeIdentityClient.createRegionSubscription(req);
            } catch (com.oracle.bmc.model.BmcException ex) {
                int code = ex.getStatusCode();
                // 502 Bad Gateway / 504 Gateway Timeout 往往是甲骨文网关反向代理超时，但上游订阅工作流极大概率已经在后台成功触发入库
                if (code == 502 || code == 504 || code == 500) {
                    System.err.println("甲骨文网关响应 " + code + "，开始检测后台是否已成功受理该区域订阅...");
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

                    // 1) 校验当前已订阅列表，看是否已经入库
                    try {
                        var verifySubs = clients.getIdentity().listRegionSubscriptions(
                            com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest.builder()
                                .tenancyId(tenancyId)
                                .build()
                        ).getItems();
                        if (verifySubs != null) {
                            for (var rs : verifySubs) {
                                if (cleanTarget.equalsIgnoreCase(rs.getRegionName()) ||
                                    cleanTarget.equalsIgnoreCase(rs.getRegionKey()) ||
                                    canonicalName.equalsIgnoreCase(rs.getRegionName()) ||
                                    regionKey.equalsIgnoreCase(rs.getRegionKey())) {
                                    String st = (rs.getStatus() != null) ? rs.getStatus().getValue() : "IN_PROGRESS";
                                    OciCloudService.SUBSCRIBED_REGIONS_CACHE.remove(accountName);
                                    res.put("success", true);
                                    res.put("account", accountName);
                                    res.put("regionName", canonicalName != null ? canonicalName : regionKey.toLowerCase());
                                    res.put("regionKey", regionKey);
                                    res.put("homeRegion", homeRegion);
                                    res.put("status", st);
                                    res.put("message", "✅ 区域 [" + (canonicalName != null ? canonicalName : regionKey) + " (" + regionKey + ")] 订阅已被甲骨文后台成功接收并排队处理中（网关通信超时返回 502，但已确认后端已入队，状态: " + st + "）！");
                                    return res;
                                }
                            }
                        }
                    } catch (Exception ignored) {}

                    // 2) 携带相同 opcRetryToken 幂等重试一次
                    try {
                        resp = homeIdentityClient.createRegionSubscription(req);
                    } catch (com.oracle.bmc.model.BmcException retryEx) {
                        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                        try {
                            var verifySubsAgain = clients.getIdentity().listRegionSubscriptions(
                                com.oracle.bmc.identity.requests.ListRegionSubscriptionsRequest.builder()
                                    .tenancyId(tenancyId)
                                    .build()
                            ).getItems();
                            if (verifySubsAgain != null) {
                                for (var rs : verifySubsAgain) {
                                    if (cleanTarget.equalsIgnoreCase(rs.getRegionName()) ||
                                        cleanTarget.equalsIgnoreCase(rs.getRegionKey()) ||
                                        canonicalName.equalsIgnoreCase(rs.getRegionName()) ||
                                        regionKey.equalsIgnoreCase(rs.getRegionKey())) {
                                        String st = (rs.getStatus() != null) ? rs.getStatus().getValue() : "IN_PROGRESS";
                                        OciCloudService.SUBSCRIBED_REGIONS_CACHE.remove(accountName);
                                        res.put("success", true);
                                        res.put("account", accountName);
                                        res.put("regionName", canonicalName != null ? canonicalName : regionKey.toLowerCase());
                                        res.put("regionKey", regionKey);
                                        res.put("homeRegion", homeRegion);
                                        res.put("status", st);
                                        res.put("message", "✅ 区域 [" + (canonicalName != null ? canonicalName : regionKey) + " (" + regionKey + ")] 订阅已被甲骨文后台成功接收并处理中（当前状态: " + st + "）！");
                                        return res;
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                        throw retryEx;
                    }
                } else {
                    throw ex;
                }
            }

            // 清理已订阅区域缓存
            OciCloudService.SUBSCRIBED_REGIONS_CACHE.remove(accountName);

            String statusStr = (resp != null && resp.getRegionSubscription() != null && resp.getRegionSubscription().getStatus() != null)
                ? resp.getRegionSubscription().getStatus().getValue() : "IN_PROGRESS";

            res.put("success", true);
            res.put("account", accountName);
            res.put("regionName", canonicalName != null ? canonicalName : regionKey.toLowerCase());
            res.put("regionKey", regionKey);
            res.put("homeRegion", homeRegion);
            res.put("status", statusStr);
            res.put("message", "✅ 区域 [" + (canonicalName != null ? canonicalName : regionKey) + " (" + regionKey + ")] 订阅请求已成功提交！甲骨文正在后台配置该区域。");
            return res;
        } catch (com.oracle.bmc.model.BmcException bmcEx) {
            String msg = bmcEx.getMessage();
            int status = bmcEx.getStatusCode();
            if (status == 404) {
                msg = "未找到目标实体 (404 EntityNotFound)：请检查输入的 Region Key (当前解析为: " + regionKey + ") 是否准确；若使用的是非主区域凭证，请确认请求已正确路由至主区域 (" + homeRegion + ")。原错误: " + msg;
            } else if (status == 502 || status == 504) {
                msg = "甲骨文官方网关超时 (HTTP " + status + " Bad Gateway)：此错误为甲骨文官方跨区域控制面响应超时导致，但订阅请求极大概率已在甲骨文后台排队处理中！请稍候 1~2 分钟刷新区域列表查看状态，或登录甲骨文云官网控制台（管理 -> 区域）核对。原错误: " + msg;
            } else if (status == 400 || status == 403) {
                if (msg != null && (msg.contains("multiregion is not supported") || msg.contains("Free Tier") || msg.contains("trial") || msg.contains("Trial") || msg.contains("not supported for this tenancy"))) {
                    msg = "甲骨文官方限制：免费试用/未升级账户 (Free Tier) 不允许订阅多区域。需先在甲骨文控制台升级为 PAYG 付费账户后方可开通多区域。原错误: " + msg;
                } else if (msg != null && (msg.contains("AlreadyExists") || msg.contains("already subscribed"))) {
                    msg = "该区域在甲骨文租户中已经存在订阅记录。";
                } else if (msg != null && (msg.contains("NotAuthorized") || msg.contains("Authorization failed"))) {
                    msg = "权限不足：当前 API 用户缺少管理租户订阅权限 (TENANCY_UPDATE 或 manage tenancy-subscriptions in tenancy)。";
                }
            }
            res.put("success", false);
            res.put("error", "甲骨文 API 拒绝订阅: " + (msg != null ? msg : "未知错误 (HTTP " + status + ")"));
            return res;
        } catch (Exception e) {
            res.put("success", false);
            res.put("error", "提交区域订阅失败: " + e.getMessage());
            return res;
        }
    }
}

