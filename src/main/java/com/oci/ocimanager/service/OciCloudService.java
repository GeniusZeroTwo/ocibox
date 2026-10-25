package com.oci.ocimanager.service;

import com.oci.ocimanager.config.OciProperties;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OCI 云服务门面（Facade）。
 *
 * 结构重构说明：原 2700+ 行的单体服务已按业务域拆分为六个域服务，
 * 本类保留全部对外方法签名（一行委托）与跨域共享的静态缓存，外部调用方无需任何改动：
 * - {@link OciClientManagerService} 客户端与认证
 * - {@link OciComputeInstanceService} 计算实例（同步/操作/创建/重装）
 * - {@link OciNetworkService} 网络（端口/子网/IP/路由/NLB）
 * - {@link OciBootVolumeService} 启动盘
 * - {@link OciAccountBillingService} 账单/流量/汇率/账号状态
 * - {@link OciQuotaService} 配额
 * 区域解析工具见 {@link OciRegionUtil}，SDK 客户端包装器见 {@link OciClientWrapper}。
 */
@Service
public class OciCloudService {

    // 结构重构：客户端/认证域服务（门面委托目标）
    @Autowired
    private OciClientManagerService clientManager;

    // 结构重构：配额域服务
    @Autowired
    private OciQuotaService quotaService;

    // 结构重构：启动盘域服务
    @Autowired
    private OciBootVolumeService bootVolumeService;

    // 结构重构：网络域服务
    @Autowired
    private OciNetworkService networkService;

    // 结构重构：账单/账号状态域服务
    @Autowired
    private OciAccountBillingService billingService;

    // 结构重构：计算实例域服务
    @Autowired
    private OciComputeInstanceService computeService;

    // ==================== 跨域共享静态缓存（物理保留在门面，域服务经 OciCloudService.X 访问） ====================

    public static final Map<String, Map<String, String>> INSTANCE_CACHE = new ConcurrentHashMap<>();

    // 🌟 【内存优化 + 并发安全】限制认证 Provider 与客户端包装器的最大常驻数量 (LRU 淘汰)，
    // 淘汰时延迟 45 秒异步关闭底层连接池，彻底杜绝多线程并发同步时将正在执行 HTTP 请求的客户端提前 closeAll()！
    private static final int MAX_PROVIDER_CACHE_SIZE = 64;
    private static final int MAX_CLIENT_CACHE_SIZE = 24;

    static final Map<String, SimpleAuthenticationDetailsProvider> PROVIDER_CACHE = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, SimpleAuthenticationDetailsProvider>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SimpleAuthenticationDetailsProvider> eldest) {
                    return size() > MAX_PROVIDER_CACHE_SIZE;
                }
            }
    );

    // 🌟 新增优化：镜像、可用区、子网级别的静态内存缓存，彻底切断重试时的冗余发包
    static final Map<String, java.util.List<String>> AD_LIST_CACHE = new ConcurrentHashMap<>();
    static final Map<String, String> IMAGE_CACHE = new ConcurrentHashMap<>();
    static final Map<String, String> SUBNET_CACHE = new ConcurrentHashMap<>();
    static final Map<String, java.util.List<String>> SUBSCRIBED_REGIONS_CACHE = new ConcurrentHashMap<>();

    // 🌟 核心并发锁：保证多线程并发时各个租户独立初始化网络时不冲突
    static final Map<String, Object> TENANCY_LOCKS = new ConcurrentHashMap<>();

    static final Map<String, OciClientWrapper> CLIENT_CACHE = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, OciClientWrapper>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, OciClientWrapper> eldest) {
                    if (size() > MAX_CLIENT_CACHE_SIZE) {
                        OciClientWrapper evicted = eldest.getValue();
                        if (evicted != null) {
                            java.util.concurrent.CompletableFuture.delayedExecutor(45, java.util.concurrent.TimeUnit.SECONDS)
                                    .execute(() -> {
                                        try {
                                            evicted.closeAll();
                                        } catch (Exception ignored) {}
                                    });
                        }
                        return true;
                    }
                    return false;
                }
            }
    );
    static final Map<String, java.util.concurrent.atomic.AtomicInteger> AD_POLL_COUNTER = new ConcurrentHashMap<>();

    static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
            .build();

    // ==================== 区域解析 ====================

    // 🌟 Helper method to extract region directly from any OCI OCID (e.g. instance, volume, attachment)
    public String extractRegionFromOcid(String ocid) {
        return OciRegionUtil.extractRegionFromOcid(ocid);
    }

    public String getRegionForResource(String ocid) {
        return OciRegionUtil.getRegionForResource(ocid);
    }

    // ==================== 客户端与认证 ====================

    public SimpleAuthenticationDetailsProvider getProvider(String accountName, OciProperties.Account account) throws Exception {
        return clientManager.getProvider(accountName, account);
    }

    public void clearProviderCache() {
        clientManager.clearProviderCache();
    }

    public void clearClientCacheForAccount(String accountName) {
        clientManager.clearClientCacheForAccount(accountName);
    }

    public void clearRegionsCache() {
        clientManager.clearRegionsCache();
    }

    public java.util.List<String> getSubscribedRegions(String accountName) {
        return clientManager.getSubscribedRegions(accountName);
    }

    public Map<String, Object> getAccountRegionInfo(String accountName) {
        return clientManager.getAccountRegionInfo(accountName);
    }

    public Map<String, Object> subscribeRegion(String accountName, String targetRegion) {
        return clientManager.subscribeRegion(accountName, targetRegion);
    }

    public Map<String, Object> subscribeRegion(String accountName, String targetRegion, String hintHomeRegion) {
        return clientManager.subscribeRegion(accountName, targetRegion, hintHomeRegion);
    }

    public OciClientFingerprintService.ClientFingerprint getClientFingerprint(String accountName) {
        return clientManager.getClientFingerprint(accountName);
    }

    public int getAccountPhaseOffsetMs(String accountName) {
        return clientManager.getAccountPhaseOffsetMs(accountName);
    }

    // ==================== 配额 ====================

    public String getQuotaTemplate() {
        return quotaService.getQuotaTemplate();
    }

    public void saveQuotaTemplate(String template) {
        quotaService.saveQuotaTemplate(template);
    }

    public void deleteFreeTierQuota(String accountName) throws Exception {
        quotaService.deleteFreeTierQuota(accountName);
    }

    public void injectFreeTierQuota(String accountName) throws Exception {
        quotaService.injectFreeTierQuota(accountName);
    }

    // ==================== 账单 / 流量 / 账号状态 ====================

    public void recordAccountSuccess(String accountName) {
        billingService.recordAccountSuccess(accountName);
    }

    public boolean isAccountBanned(String accountName) {
        return billingService.isAccountBanned(accountName);
    }

    public String getAccountTag(String accountName) {
        return billingService.getAccountTag(accountName);
    }

    public void setAccountTag(String accountName, String tag) {
        billingService.setAccountTag(accountName, tag);
    }

    public void clearTagCache() {
        billingService.clearTagCache();
    }

    public void clearBannedCache() {
        billingService.clearBannedCache();
    }

    public boolean getCachedPaygStatus(String accountName) {
        return billingService.getCachedPaygStatus(accountName);
    }

    public String getPaygStatusString(String accountName) {
        return billingService.getPaygStatusString(accountName);
    }

    public void clearPaygCache() {
        billingService.clearPaygCache();
    }

    public void setManualPaygStatus(String accountName, boolean isPayg) {
        billingService.setManualPaygStatus(accountName, isPayg);
    }

    public void resetAccountPaygManualOverride(String accountName) {
        billingService.resetAccountPaygManualOverride(accountName);
    }

    public String getCachedCurrentMonthBill(String accountName) {
        return billingService.getCachedCurrentMonthBill(accountName);
    }

    public void clearBillCache() {
        billingService.clearBillCache();
    }

    public double getExchangeRateToCny(String currencyCode) {
        return billingService.getExchangeRateToCny(currencyCode);
    }

    public String fetchCurrentMonthBill(String accountName) {
        return billingService.fetchCurrentMonthBill(accountName);
    }

    public String getTenancyCreateTime(String accountName) {
        return billingService.getTenancyCreateTime(accountName);
    }

    public void triggerAutoFetchRegisterTime(String accountName) {
        billingService.triggerAutoFetchRegisterTime(accountName);
    }

    public boolean isValidRegisterTime(String time) {
        return billingService.isValidRegisterTime(time);
    }

    public double fetchTrafficInGb(String accountName) {
        return billingService.fetchTrafficInGb(accountName);
    }

    // ==================== 计算实例 ====================

    public Map<String, Map<String, String>> syncAllInstances() {
        return computeService.syncAllInstances();
    }

    public Map<String, Map<String, String>> updateInstancesCache(boolean forceIp) {
        return computeService.updateInstancesCache(forceIp);
    }

    public Map<String, Map<String, String>> updateInstancesCache() {
        return computeService.updateInstancesCache();
    }

    public boolean instanceAction(String ocid, String action, String accountName) {
        return computeService.instanceAction(ocid, action, accountName);
    }

    public boolean deleteInstance(String ocid, String accountName) {
        return computeService.deleteInstance(ocid, accountName);
    }

    public boolean renameInstance(String ocid, String newName, String accountName) {
        return computeService.renameInstance(ocid, newName, accountName);
    }

    public List<Map<String, String>> getAvailableImages(String accountName, String shape, String region) throws Exception {
        return computeService.getAvailableImages(accountName, shape, region);
    }

    public String launchInstance(String accountName, String instanceName, String region, String shape, String osName, String osVersion, float cpu, float ram, long disk, boolean ipv4, boolean ipv6, String rootPwd, String sshKey) throws Exception {
        return computeService.launchInstance(accountName, instanceName, region, shape, osName, osVersion, cpu, ram, disk, ipv4, ipv6, rootPwd, sshKey);
    }

    public boolean reinstallInstance(String ocid, String accountName, String instanceName, String osName, String osVersion, String customImageOcid, String sshKey, String rootPwd) throws Exception {
        return computeService.reinstallInstance(ocid, accountName, instanceName, osName, osVersion, customImageOcid, sshKey, rootPwd);
    }

    public String getInstanceStatus(String ocid, String accountName) {
        return computeService.getInstanceStatus(ocid, accountName);
    }

    public Map<String, String> refreshSingleInstance(String ocid, String accountName) throws Exception {
        return computeService.refreshSingleInstance(ocid, accountName);
    }

    // ==================== 启动盘 ====================

    public List<Map<String, String>> getBootVolumes(String accountName) throws Exception {
        return bootVolumeService.getBootVolumes(accountName);
    }

    public void invalidateBootVolumeCache(String accountName) {
        bootVolumeService.invalidateBootVolumeCache(accountName);
    }

    public boolean detachBootVolume(String attachmentId, String accountName) {
        return bootVolumeService.detachBootVolume(attachmentId, accountName);
    }

    public boolean deleteBootVolume(String bootVolumeId, String accountName) {
        return bootVolumeService.deleteBootVolume(bootVolumeId, accountName);
    }

    public boolean expandBootVolume(String bootVolumeId, Long sizeInGBs, String accountName) {
        return bootVolumeService.expandBootVolume(bootVolumeId, sizeInGBs, accountName);
    }

    public int cleanUnattachedBootVolumes(String accountName) {
        return bootVolumeService.cleanUnattachedBootVolumes(accountName);
    }

    // ==================== 网络 ====================

    public void fixRouteTables(String accountName) throws Exception {
        networkService.fixRouteTables(accountName);
    }

    public void openAllPorts(String accountName) throws Exception {
        networkService.openAllPorts(accountName);
    }

    public String getCachedIp(String ocid) {
        return networkService.getCachedIp(ocid);
    }

    public String getInstanceIp(String ocid, String accountName, boolean forceFetch) {
        return networkService.getInstanceIp(ocid, accountName, forceFetch);
    }

    public String getInstanceIp(String ocid, String accountName) {
        return networkService.getInstanceIp(ocid, accountName);
    }

    public String changeInstanceIp(String ocid, String accountName) {
        return networkService.changeInstanceIp(ocid, accountName);
    }

    public String getIpLocation(String ip) {
        return networkService.getIpLocation(ip);
    }

    public String enableAmd500Mbps(String ocid, String account) throws Exception {
        return networkService.enableAmd500Mbps(ocid, account);
    }

    public String enable500Mbps(String ocid, String account) throws Exception {
        return networkService.enable500Mbps(ocid, account);
    }

    public String disable500Mbps(String ocid, String account) throws Exception {
        return networkService.disable500Mbps(ocid, account);
    }

    public String getCachedIpv6(String ocid) {
        return networkService.getCachedIpv6(ocid);
    }

    public String getInstanceIpv6(String ocid, String accountName) {
        return networkService.getInstanceIpv6(ocid, accountName);
    }

    public String getInstanceIpv6(String ocid, String accountName, boolean forceRefresh) {
        return networkService.getInstanceIpv6(ocid, accountName, forceRefresh);
    }

    public String enableIpv6(String ocid, String account) throws Exception {
        return networkService.enableIpv6(ocid, account);
    }

    public boolean disableIpv6(String ocid, String account) throws Exception {
        return networkService.disableIpv6(ocid, account);
    }
}
