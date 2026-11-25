package com.oci.ocimanager.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oci.ocimanager.config.OciProperties;
import com.oracle.bmc.core.model.*;
import com.oracle.bmc.core.requests.*;
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest;
import com.oracle.bmc.model.BmcException;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 计算实例域服务：实例清单同步、启停/删除/重命名、镜像查询、开机（抢机）、重装、状态查询、cloud-init 构造。
 * 结构重构自 OciCloudService（门面保留原对外方法并委托到此处）。
 */
@Service
public class OciComputeInstanceService {

    @Autowired
    private OciProperties ociProperties;

    @Autowired
    private LocalCacheService redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OciClientManagerService clientManager;

    @Autowired
    private OciNetworkService networkService;

    @Autowired
    private OciBootVolumeService bootVolumeService;

    @Autowired
    private OciAccountBillingService billingService;

    @Autowired
    private OciClientFingerprintService fingerprintService;

    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private NotificationService notificationService;

    private static final long IMAGE_CACHE_TTL_MS = 6L * 3600L * 1000L;
    private static final long SUBNET_CACHE_TTL_MS = 3600L * 1000L;
    private static final Set<String> REINSTALLING_INSTANCES = ConcurrentHashMap.newKeySet();

    private final java.util.concurrent.ScheduledExecutorService reinstallMonitorScheduler =
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "oci-reinstall-monitor");
                t.setDaemon(true);
                return t;
            });

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        reinstallMonitorScheduler.shutdownNow();
    }

    /**
     * 🌟 强化重构版：初始化 OS 环境，强行放行所有底层防火墙 (iptables, firewalld, ufw)
     * 同时基于各账号专属种子多态化生成 drop-in 配置文件名与结构，消除跨账号固定 user_data Base64 指纹
     */
    // 🔒 【命令注入修复】cloud-init 是 YAML 文档；rootPwd/sshKey 中的换行会越出当前字段，
    // 注入任意 root 级 runcmd。此处将其压成单行并剔除控制字符，阻断注入。
    private static String sanitizeCloudInitValue(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c >= 0x20 && c != 0x7F) sb.append(c);
        }
        return sb.toString();
    }

    private static final java.util.regex.Pattern SSH_PUBKEY_PATTERN = java.util.regex.Pattern.compile(
            "^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)|sk-ssh-ed25519@openssh\\.com|sk-ecdsa-sha2-nistp256@openssh\\.com)\\s+[A-Za-z0-9+/=]{16,}(\\s+[^\\r\\n]*)?$");

    /** 校验 OpenSSH 公钥格式，拦截控制字符与异常注入载荷 */
    public static boolean isValidSshPublicKey(String raw) {
        if (raw == null) return false;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 8192) return false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7F) return false;
        }
        return SSH_PUBKEY_PATTERN.matcher(trimmed).matches();
    }

    String buildCloudInitUserData(String rootPwd, String sshKey, String accountName) {
        String safePwd = sanitizeCloudInitValue(rootPwd);
        String trimmedPwd = (safePwd != null) ? safePwd.trim() : "";
        String safeKey = sanitizeCloudInitValue(sshKey);
        String trimmedKey = (safeKey != null) ? safeKey.trim() : "";

        boolean hasPwd = !trimmedPwd.isEmpty() && !"null".equalsIgnoreCase(trimmedPwd);
        boolean hasSsh = !trimmedKey.isEmpty() && !"null".equalsIgnoreCase(trimmedKey)
                && SSH_PUBKEY_PATTERN.matcher(trimmedKey).matches();

        int seed = (accountName != null && !accountName.isEmpty()) ? (accountName.hashCode() & Integer.MAX_VALUE) : 0;
        String[][] dropInPairs = {
                {"01-sshd-auth.conf", "90-sshd-override.conf"},
                {"05-cloud-ssh.conf", "95-cloud-ssh.conf"},
                {"10-root-access.conf", "98-root-access.conf"},
                {"02-openssh-root.conf", "92-openssh-root.conf"},
                {"00-custom-ssh.conf", "99-custom-ssh.conf"},
                {"08-server-auth.conf", "96-server-auth.conf"}
        };
        String[] chosenPair = dropInPairs[seed % dropInPairs.length];
        String firstConf = chosenPair[0];
        String lastConf = chosenPair[1];

        StringBuilder cloudConfig = new StringBuilder();
        cloudConfig.append("#cloud-config\n");
        cloudConfig.append("disable_root: false\n");

        if (hasPwd) {
            cloudConfig.append("ssh_pwauth: true\n");
            cloudConfig.append("chpasswd:\n");
            cloudConfig.append("  list: |\n");
            cloudConfig.append("    root:").append(trimmedPwd).append("\n");
            cloudConfig.append("  expire: ").append((seed % 2 == 0) ? "false" : "False").append("\n");
        } else {
            cloudConfig.append("ssh_pwauth: false\n");
        }

        // 🌟 【解除锁定核心】Cloud-init 在声明 users 时默认 lock_passwd: true。
        // 若设置了密码，显式指定 lock_passwd: false；纯密钥模式下保持密码锁定，仅注入授权公钥。
        if (hasPwd || hasSsh) {
            cloudConfig.append("users:\n");
            cloudConfig.append("  - default\n");
            cloudConfig.append("  - name: root\n");
            if (hasPwd) {
                cloudConfig.append("    lock_passwd: false\n");
            }
            if (hasSsh) {
                cloudConfig.append("    ssh_authorized_keys:\n");
                cloudConfig.append("      - '").append(trimmedKey.replace("'", "''")).append("'\n");
            }
        }

        cloudConfig.append("runcmd:\n");
        cloudConfig.append("  - mkdir -p /etc/ssh/sshd_config.d\n");

        if (hasPwd) {
            // 密码模式（或密码+密钥双模式）：仅设置 root 自身密码，绝不将同一密码横向覆盖到 ubuntu/opc/debian/centos/rocky 等系统账号
            cloudConfig.append("  - sed -i 's/^#\\?PermitRootLogin.*/PermitRootLogin yes/g' /etc/ssh/sshd_config\n");
            cloudConfig.append("  - sed -i 's/^#\\?PermitRootLogin.*/PermitRootLogin yes/g' /etc/ssh/sshd_config.d/*.conf 2>/dev/null || true\n");
            cloudConfig.append("  - grep -q '^PermitRootLogin' /etc/ssh/sshd_config || echo 'PermitRootLogin yes' >> /etc/ssh/sshd_config\n");
            cloudConfig.append("  - echo 'PermitRootLogin yes' > /etc/ssh/sshd_config.d/").append(firstConf).append("\n");
            cloudConfig.append("  - sed -i 's/^#\\?PasswordAuthentication.*/PasswordAuthentication yes/g' /etc/ssh/sshd_config\n");
            cloudConfig.append("  - sed -i 's/^#\\?PasswordAuthentication.*/PasswordAuthentication yes/g' /etc/ssh/sshd_config.d/*.conf 2>/dev/null || true\n");
            cloudConfig.append("  - grep -q '^PasswordAuthentication' /etc/ssh/sshd_config || echo 'PasswordAuthentication yes' >> /etc/ssh/sshd_config\n");
            cloudConfig.append("  - sed -i 's/^#\\?KbdInteractiveAuthentication.*/KbdInteractiveAuthentication yes/g' /etc/ssh/sshd_config 2>/dev/null || true\n");
            cloudConfig.append("  - sed -i 's/^#\\?KbdInteractiveAuthentication.*/KbdInteractiveAuthentication yes/g' /etc/ssh/sshd_config.d/*.conf 2>/dev/null || true\n");
            cloudConfig.append("  - echo 'PasswordAuthentication yes' >> /etc/ssh/sshd_config.d/").append(firstConf).append("\n");
            cloudConfig.append("  - echo 'KbdInteractiveAuthentication yes' >> /etc/ssh/sshd_config.d/").append(firstConf).append("\n");
            cloudConfig.append("  - cp -f /etc/ssh/sshd_config.d/").append(firstConf).append(" /etc/ssh/sshd_config.d/").append(lastConf).append("\n");

            String rootAuth = "root:" + trimmedPwd;
            String b64Root = Base64.getEncoder().encodeToString(rootAuth.getBytes(StandardCharsets.UTF_8));
            cloudConfig.append("  - echo '").append(b64Root).append("' | base64 -d | chpasswd\n");
            cloudConfig.append("  - passwd -u root || true\n");
        } else {
            // 纯 SSH 密钥安全模式：严格设为 prohibit-password 并彻底关闭密码/交互认证
            cloudConfig.append("  - sed -i 's/^#\\?PermitRootLogin.*/PermitRootLogin prohibit-password/g' /etc/ssh/sshd_config\n");
            cloudConfig.append("  - sed -i 's/^#\\?PermitRootLogin.*/PermitRootLogin prohibit-password/g' /etc/ssh/sshd_config.d/*.conf 2>/dev/null || true\n");
            cloudConfig.append("  - grep -q '^PermitRootLogin' /etc/ssh/sshd_config || echo 'PermitRootLogin prohibit-password' >> /etc/ssh/sshd_config\n");
            cloudConfig.append("  - sed -i 's/^#\\?PasswordAuthentication.*/PasswordAuthentication no/g' /etc/ssh/sshd_config\n");
            cloudConfig.append("  - sed -i 's/^#\\?PasswordAuthentication.*/PasswordAuthentication no/g' /etc/ssh/sshd_config.d/*.conf 2>/dev/null || true\n");
            cloudConfig.append("  - grep -q '^PasswordAuthentication' /etc/ssh/sshd_config || echo 'PasswordAuthentication no' >> /etc/ssh/sshd_config\n");
            cloudConfig.append("  - sed -i 's/^#\\?KbdInteractiveAuthentication.*/KbdInteractiveAuthentication no/g' /etc/ssh/sshd_config 2>/dev/null || true\n");
            cloudConfig.append("  - sed -i 's/^#\\?KbdInteractiveAuthentication.*/KbdInteractiveAuthentication no/g' /etc/ssh/sshd_config.d/*.conf 2>/dev/null || true\n");
            cloudConfig.append("  - echo 'PermitRootLogin prohibit-password' > /etc/ssh/sshd_config.d/").append(firstConf).append("\n");
            cloudConfig.append("  - echo 'PasswordAuthentication no' >> /etc/ssh/sshd_config.d/").append(firstConf).append("\n");
            cloudConfig.append("  - echo 'KbdInteractiveAuthentication no' >> /etc/ssh/sshd_config.d/").append(firstConf).append("\n");
            cloudConfig.append("  - cp -f /etc/ssh/sshd_config.d/").append(firstConf).append(" /etc/ssh/sshd_config.d/").append(lastConf).append("\n");
        }

        // 重载并重启 SSH 服务（同时兼容 ssh.service 与 Ubuntu 22.10/24.04 的 ssh.socket），保留系统原生防火墙并仅确保 22 端口可达
        cloudConfig.append("  - if command -v ufw >/dev/null 2>&1 && ufw status | grep -q 'Status: active'; then ufw allow 22/tcp || true; fi\n");
        cloudConfig.append("  - if command -v firewall-cmd >/dev/null 2>&1 && systemctl is-active --quiet firewalld; then firewall-cmd --permanent --add-service=ssh && firewall-cmd --reload || true; fi\n");
        cloudConfig.append("  - systemctl daemon-reload || true\n");
        cloudConfig.append("  - systemctl restart ssh || systemctl restart sshd || true\n");
        cloudConfig.append("  - systemctl restart ssh.socket || true\n");

        return Base64.getEncoder().encodeToString(cloudConfig.toString().getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, Map<String, String>> syncAllInstances() {
        return OciCloudService.INSTANCE_CACHE;
    }

    public Map<String, Map<String, String>> updateInstancesCache(boolean forceIp) {
        Set<String> succeededAccountRegions = ConcurrentHashMap.newKeySet();
        Map<String, Map<String, String>> newInstances = fetchFromOracle(forceIp, succeededAccountRegions);
        if (newInstances != null && (!newInstances.isEmpty() || !succeededAccountRegions.isEmpty())) {
            // 🚀 【并发可见性与防误删修复】：
            // 1. 先合并本次拉取到的所有最新实例数据；
            // 2. 仅清理“本次确实同步成功 (succeededAccountRegions 包含该 账号|区域) 但云端已不存在”的实例，
            //    以及已被移出配置文件的账号实例；
            // 3. 若某账号或区域因瞬时网络波动/代理超时未能返回，100% 保留其原有缓存实例，绝不误删客户名下机器！
            OciCloudService.INSTANCE_CACHE.putAll(newInstances);
            Set<String> validAccounts = ociProperties.getAccounts().keySet();
            OciCloudService.INSTANCE_CACHE.entrySet().removeIf(entry -> {
                String ocid = entry.getKey();
                if (newInstances.containsKey(ocid)) {
                    return false;
                }
                Map<String, String> oldInfo = entry.getValue();
                if (oldInfo == null) return true;
                String acc = oldInfo.get("account");
                if (acc == null || !validAccounts.contains(acc)) {
                    return true;
                }
                String reg = oldInfo.get("region");
                if (reg != null && succeededAccountRegions.contains(acc + "|" + reg)) {
                    return true;
                }
                return false;
            });
        }
        return OciCloudService.INSTANCE_CACHE;
    }

    public Map<String, Map<String, String>> updateInstancesCache() {
        return updateInstancesCache(false);
    }

    private Map<String, Map<String, String>> fetchFromOracle(boolean forceIp, Set<String> succeededAccountRegions) {
        Map<String, Map<String, String>> allInstances = new ConcurrentHashMap<>();
        Map<String, OciProperties.Account> accounts = ociProperties.getAccounts();
        java.util.concurrent.Semaphore concurrencyLimiter = new java.util.concurrent.Semaphore(8);

        try (ExecutorService vte = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(accounts.size());

            for (Map.Entry<String, OciProperties.Account> entry : accounts.entrySet()) {
                String accName = entry.getKey();
                OciProperties.Account accConfig = entry.getValue();
                futures.add(vte.submit(() -> {
                    try {
                        concurrencyLimiter.acquire();
                        try {
                            fetchSingleAccount(accName, accConfig, forceIp, allInstances, succeededAccountRegions);
                        } finally {
                            concurrencyLimiter.release();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            for (Future<?> f : futures) {
                try {
                    f.get(5, TimeUnit.MINUTES);
                } catch (Exception e) {
                    System.err.println("fetchFromOracle: 某账号并发查询异常: " + e.getMessage());
                }
            }
        }
        return allInstances;
    }

    private void fetchSingleAccount(String accName, OciProperties.Account accConfig, boolean forceIp, Map<String, Map<String, String>> allInstances, Set<String> succeededAccountRegions) {
        try {
            // 🌟 多账号时序差异化防风控：各账号独立固定相位偏移与动态微抖动，彻底打碎并发锁步请求特征
            int phaseOffset = clientManager.getAccountPhaseOffsetMs(accName);
            if (phaseOffset > 0) {
                try { Thread.sleep(phaseOffset); } catch (InterruptedException ignored) {}
            }

            var clients = clientManager.getOciClients(accName);
            Map<String, Long> instanceDiskMap = new HashMap<>();
            
            try {
                List<Map<String, String>> bvList = null;
                String bvJson = redisTemplate.opsForValue().get("oci:bv_cache:" + accName);
                if (bvJson != null) {
                    bvList = objectMapper.readValue(bvJson, new TypeReference<List<Map<String, String>>>(){});
                } else {
                    bvList = bootVolumeService.fetchBootVolumeData(accName);
                    redisTemplate.opsForValue().set("oci:bv_cache:" + accName, objectMapper.writeValueAsString(bvList), 3, TimeUnit.MINUTES);
                }

                for (Map<String, String> bv : bvList) {
                    String instanceId = bv.get("instanceId");
                    String sizeStr = bv.get("size");
                    if (instanceId != null && !instanceId.isEmpty() && sizeStr != null) {
                        try {
                            instanceDiskMap.put(instanceId, Long.parseLong(sizeStr));
                        } catch (NumberFormatException e) {
                            System.err.println("解析硬盘大小异常: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("同步硬盘大小失败 [" + accName + "]: " + e.getMessage());
            }

            java.util.List<String> regions = clientManager.getSubscribedRegions(accName);
            java.util.List<String> compartmentIds = clientManager.getActiveCompartmentIds(accName);
            if (compartmentIds.isEmpty() && accConfig.getTenancy() != null) {
                compartmentIds = java.util.List.of(accConfig.getTenancy());
            }

            for (String region : regions) {
                try {
                    var computeClient = clients.getComputeClientForRegion(region);
                    boolean regionComplete = true;
                    for (String compId : compartmentIds) {
                        try {
                            var response = computeClient.listInstances(
                                    ListInstancesRequest.builder()
                                            .compartmentId(compId)
                                            .build()
                            );
                            billingService.recordAccountSuccess(accName);

                            for (Instance instance : response.getItems()) {
                                if (instance.getLifecycleState() == Instance.LifecycleState.Terminated) {
                                    continue;
                                }

                                Map<String, String> info = new HashMap<>();
                                info.put("name", "[" + accName + "] " + instance.getDisplayName());
                                info.put("state", instance.getLifecycleState().getValue());
                                info.put("account", accName);
                                info.put("shape", instance.getShape());
                                info.put("compartmentId", compId);

                                if (instance.getShapeConfig() != null) {
                                    Float cpu = instance.getShapeConfig().getOcpus();
                                    Float mem = instance.getShapeConfig().getMemoryInGBs();
                                    if (cpu != null) {
                                        info.put("cpu", String.valueOf(cpu));
                                    }
                                    if (mem != null) {
                                        info.put("memory", String.valueOf(mem));
                                    }
                                }

                                Long disk = instanceDiskMap.get(instance.getId());
                                if (disk != null) {
                                    info.put("disk", String.valueOf(disk));
                                }

                                info.put("region", region);
                                allInstances.put(instance.getId(), info);

                                if (forceIp && instance.getLifecycleState() == Instance.LifecycleState.Running) {
                                    try {
                                        networkService.getInstanceIp(instance.getId(), accName, true);
                                        Thread.sleep(300);
                                    } catch (Exception e) {
                                        System.err.println("强制刷新IP异常: " + e.getMessage());
                                    }
                                }
                            }
                        } catch (Exception compEx) {
                            regionComplete = false;
                            System.err.println("获取子区间实例失败 [" + accName + " - " + region + " - " + compId + "]: " + compEx.getMessage());
                        }
                    }
                    if (regionComplete && succeededAccountRegions != null) {
                        succeededAccountRegions.add(accName + "|" + region);
                    }
                } catch (Exception e) {
                    System.err.println("获取区域实例失败 [" + accName + " - " + region + "]: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            String errMsg = e.getMessage();
            if (errMsg == null) {
                errMsg = e.getClass().getSimpleName() + " (遇到空指针，请排查该账号下是否有配置漏填)";
            }
            System.out.println("同步账号 [" + accName + "] 失败: " + errMsg);
        }
    }

    public boolean instanceAction(String ocid, String action, String accountName) {
        try {
            var clients = clientManager.getOciClients(accountName);
            String region = OciRegionUtil.getRegionForResource(ocid);
            var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();
            computeClient.instanceAction(InstanceActionRequest.builder()
                    .instanceId(ocid)
                    .action(action)
                    .build());
            return true;
        } catch (com.oracle.bmc.model.BmcException e) {
            if (e.getStatusCode() == 409 && e.getMessage() != null && e.getMessage().contains("currently being modified")) {
                System.out.println("实例 [" + action + "] 被拒绝: 当前实例正在修改或切换状态中，请稍后再试 (409 Conflict)");
            } else {
                System.err.println("实例操作失败(API异常) [" + action + "]: " + e.getMessage());
            }
            return false;
        } catch (Exception e) {
            System.err.println("实例操作失败 [" + action + "]: " + e.getMessage());
            return false;
        }
    }

    public boolean deleteInstance(String ocid, String accountName) {
        try {
            var clients = clientManager.getOciClients(accountName);
            String region = OciRegionUtil.getRegionForResource(ocid);
            var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();
            computeClient.terminateInstance(TerminateInstanceRequest.builder()
                    .instanceId(ocid)
                    .preserveBootVolume(false)
                    .build());
            return true;
        } catch (Exception e) {
            System.err.println("删除实例失败: " + e.getMessage());
            return false;
        }
    }

    public boolean renameInstance(String ocid, String newName, String accountName) {
        try {
            var clients = clientManager.getOciClients(accountName);
            String region = OciRegionUtil.getRegionForResource(ocid);
            var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();

            UpdateInstanceDetails details = UpdateInstanceDetails.builder()
                    .displayName(newName)
                    .build();

            computeClient.updateInstance(UpdateInstanceRequest.builder()
                    .instanceId(ocid)
                    .updateInstanceDetails(details)
                    .build());

            return true;
        } catch (Exception e) {
            System.err.println("重命名实例失败 [" + accountName + "]: " + e.getMessage());
            return false;
        }
    }

    public List<Map<String, String>> getAvailableImages(String accountName, String shape, String region) throws Exception {
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        var clients = clientManager.getOciClients(accountName);
        String targetRegion = (region != null && !region.trim().isEmpty()) ? region.trim() : accConfig.getRegion();
        var computeClient = clients.getComputeClientForRegion(targetRegion);

        var imgRes = computeClient.listImages(ListImagesRequest.builder()
                .compartmentId(accConfig.getTenancy())
                .shape(shape)
                .sortBy(ListImagesRequest.SortBy.Timecreated)
                .sortOrder(ListImagesRequest.SortOrder.Desc)
                .limit(200)
                .build());

        Set<String> seen = new HashSet<>();
        List<Map<String, String>> result = new ArrayList<>();
        for (Image img : imgRes.getItems()) {
            String os = img.getOperatingSystem();
            String version = img.getOperatingSystemVersion();
            if (os != null && version != null && img.getBaseImageId() == null && !os.contains("Custom")) {
                String key = os + "|" + version;
                if (!seen.contains(key)) {
                    seen.add(key);
                    Map<String, String> map = new HashMap<>();
                    map.put("value", key);
                    map.put("display", os + " " + version);
                    result.add(map);
                }
            }
        }
        return result;
    }

    public String launchInstance(String accountName, String instanceName, String region, String shape, String osName, String osVersion, float cpu, float ram, long disk, boolean ipv4, boolean ipv6, String rootPwd, String sshKey) throws Exception {
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        var clients = clientManager.getOciClients(accountName);
        String tenancy = accConfig.getTenancy();
        
        String targetRegion = (region != null && !region.trim().isEmpty()) ? region : accConfig.getRegion();

        var identityClient = clients.getIdentityClientForRegion(targetRegion);
        var computeClient = clients.getComputeClientForRegion(targetRegion);
        var vncClient = clients.getVncClientForRegion(targetRegion);

        java.util.List<String> adNames = OciCloudService.AD_LIST_CACHE.computeIfAbsent(tenancy + "|" + targetRegion, k -> {
            var adRes = identityClient.listAvailabilityDomains(ListAvailabilityDomainsRequest.builder().compartmentId(tenancy).build());
            if (adRes.getItems().isEmpty()) {
                throw new RuntimeException("未找到可用区(AD)");
            }
            java.util.List<String> names = new java.util.ArrayList<>();
            for (var item : adRes.getItems()) {
                names.add(item.getName());
            }
            return names;
        });

        long nowMs = System.currentTimeMillis();
        String imgKey = tenancy + "|" + targetRegion + "|" + shape + "|" + osName + "|" + osVersion;
        Long imgCachedAt = OciCloudService.IMAGE_CACHE_TIME.get(imgKey);
        if (imgCachedAt != null && (nowMs - imgCachedAt) > IMAGE_CACHE_TTL_MS) {
            OciCloudService.IMAGE_CACHE.remove(imgKey);
            OciCloudService.IMAGE_CACHE_TIME.remove(imgKey);
        }
        String imageId = OciCloudService.IMAGE_CACHE.computeIfAbsent(imgKey, k -> {
            var imgRes = computeClient.listImages(ListImagesRequest.builder()
                    .compartmentId(tenancy)
                    .operatingSystem(osName)
                    .operatingSystemVersion(osVersion)
                    .shape(shape)
                    .sortBy(ListImagesRequest.SortBy.Timecreated)
                    .sortOrder(ListImagesRequest.SortOrder.Desc)
                    .build());
            if (imgRes.getItems().isEmpty()) {
                throw new RuntimeException("在该地区未找到匹配此架构的 " + osName + " " + osVersion + " 官方镜像");
            }
            OciCloudService.IMAGE_CACHE_TIME.put(k, System.currentTimeMillis());
            return imgRes.getItems().get(0).getId();
        });

        String subnetKey = tenancy + "|" + targetRegion;
        Long subnetCachedAt = OciCloudService.SUBNET_CACHE_TIME.get(subnetKey);
        if (subnetCachedAt != null && (nowMs - subnetCachedAt) > SUBNET_CACHE_TTL_MS) {
            OciCloudService.SUBNET_CACHE.remove(subnetKey);
            OciCloudService.SUBNET_CACHE_TIME.remove(subnetKey);
        }
        String subnetId = OciCloudService.SUBNET_CACHE.computeIfAbsent(subnetKey, k -> {
            String sid = networkService.getOrCreateSubnetId(vncClient, tenancy);
            OciCloudService.SUBNET_CACHE_TIME.put(k, System.currentTimeMillis());
            return sid;
        });

        boolean hasPwd = (rootPwd != null && !rootPwd.trim().isEmpty() && !"null".equalsIgnoreCase(rootPwd.trim()));
        boolean hasSsh = isValidSshPublicKey(sshKey);

        Map<String, String> metadata = new HashMap<>();
        if (hasSsh) {
            metadata.put("ssh_authorized_keys", sshKey.trim());
        }

        if (hasPwd || hasSsh) {
            metadata.put("user_data", buildCloudInitUserData(hasPwd ? rootPwd : null, hasSsh ? sshKey : null, accountName));
        }

        // 🔒 【敏感信息泄漏修复】不再把 root 明文密码写入 freeform tag。
        // freeform tag 对任何拥有 tenancy read 权限者可见，且会显示在 OCI 控制台；
        // 该标签在代码中从未被读取，移除无任何功能影响。
        Map<String, String> freeformTags = new HashMap<>();

        String finalName = (instanceName != null && !instanceName.trim().isEmpty())
                ? instanceName.trim()
                : fingerprintService.getAccountDefaultInstanceName(accountName, tenancy, shape);

        LaunchInstanceDetails.Builder detailsBuilder = LaunchInstanceDetails.builder()
                .compartmentId(tenancy)
                .shape(shape)
                .sourceDetails(InstanceSourceViaImageDetails.builder()
                        .imageId(imageId)
                        .bootVolumeSizeInGBs(disk)
                        .build())
                .createVnicDetails(CreateVnicDetails.builder()
                        .subnetId(subnetId)
                        .assignPublicIp(ipv4)
                        .assignIpv6Ip(ipv6)
                        .build())
                .metadata(metadata)
                .freeformTags(freeformTags.isEmpty() ? null : freeformTags)
                .displayName(finalName);

        if (shape != null && shape.contains("Flex")) {
            detailsBuilder.shapeConfig(LaunchInstanceShapeConfigDetails.builder()
                    .ocpus(cpu)
                    .memoryInGBs(ram)
                    .build());
        }

        java.util.concurrent.atomic.AtomicInteger counter = OciCloudService.AD_POLL_COUNTER.computeIfAbsent(accountName + "_" + finalName, k -> new java.util.concurrent.atomic.AtomicInteger(0));
        int safeCount = counter.getAndIncrement() & Integer.MAX_VALUE;
        String currentAd = adNames.get(safeCount % adNames.size());
        
        detailsBuilder.availabilityDomain(currentAd);
        
        try {
            // 🌟 请求级 429 专用智能重试策略
            // 全局 ClientConfiguration 已设置 MaxAttempts=1，防止 SDK 对 500 "Out of host capacity"
            // 偷偷重试 8 次（这是之前 429 频发的真正元凶）。
            // 在 launchInstance 请求上覆盖全局配置，挂载仅针对 429 的退避重试：
            //   - 遇到 429 → SDK 底层自动指数退避重试（最多 4 次），日志里看不到 429
            //   - 遇到 500 → retryCondition 返回 false，立刻抛出异常，应用层秒级响应
            com.oracle.bmc.retrier.RetryConfiguration launchRetry = com.oracle.bmc.retrier.RetryConfiguration.builder()
                    .terminationStrategy(new com.oracle.bmc.waiter.MaxAttemptsTerminationStrategy(4))
                    .delayStrategy(new com.oracle.bmc.waiter.ExponentialBackoffDelayStrategyWithJitter(30000L))
                    .retryCondition(e -> e.getStatusCode() == 429)
                    .build();

            // 🌟 核心防碰撞修复：锁定该租户的抢机请求，禁止同一账号并发抢机触发 OCI API 重放防御或 SDK 签名竞态条件导致的 404/401 拦截
            Object accountLock = OciCloudService.TENANCY_LOCKS.computeIfAbsent(tenancy, k -> new Object());
            String retryToken = (clients.getFingerprint() != null)
                    ? clients.getFingerprint().generateRetryToken()
                    : java.util.UUID.randomUUID().toString();
            synchronized (accountLock) {
                var response = computeClient.launchInstance(LaunchInstanceRequest.builder()
                        .launchInstanceDetails(detailsBuilder.build())
                        .retryConfiguration(launchRetry)
                        .opcRetryToken(retryToken)
                        .build());
                return response.getInstance().getId();
            }
        } catch (com.oracle.bmc.model.BmcException e) {
            if (e.getStatusCode() == 404) {
                OciCloudService.IMAGE_CACHE.remove(imgKey);
                OciCloudService.IMAGE_CACHE_TIME.remove(imgKey);
                OciCloudService.SUBNET_CACHE.remove(subnetKey);
                OciCloudService.SUBNET_CACHE_TIME.remove(subnetKey);
            }
            throw e;
        } catch (Exception e) {
            throw e;
        }
    }

    public boolean reinstallInstance(String ocid, String accountName, String instanceName, String osName, String osVersion, String customImageOcid, String sshKey, String rootPwd) throws Exception {
        if (ocid == null || ocid.isBlank()) {
            throw new IllegalArgumentException("缺少实例 OCID");
        }
        if (!REINSTALLING_INSTANCES.add(ocid)) {
            throw new IllegalStateException("该实例当前已有重装任务正在执行中，请勿重复提交");
        }
        try {
            OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
            if (accConfig == null) {
                throw new IllegalArgumentException("账号配置不存在: " + accountName);
            }
            var clients = clientManager.getOciClients(accountName);
            String region = OciRegionUtil.getRegionForResource(ocid);
            var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();

            String targetImageId = (customImageOcid != null) ? customImageOcid.trim() : null;
            var instanceInfo = computeClient.getInstance(GetInstanceRequest.builder().instanceId(ocid).build());
            String shape = instanceInfo.getInstance().getShape();

            if (targetImageId == null || targetImageId.isEmpty()) {
                var imgRes = computeClient.listImages(ListImagesRequest.builder()
                        .compartmentId(accConfig.getTenancy())
                        .operatingSystem(osName)
                        .operatingSystemVersion(osVersion)
                        .shape(shape)
                        .sortBy(ListImagesRequest.SortBy.Timecreated)
                        .sortOrder(ListImagesRequest.SortOrder.Desc)
                        .build());

                if (imgRes.getItems().isEmpty()) {
                    throw new RuntimeException("当前区域未找到适配 [" + shape + "] 架构的 " + osName + " " + osVersion + " 官方镜像");
                }
                targetImageId = imgRes.getItems().get(0).getId();
            }

            // 🔒 重装/卸载旧盘前强制预检目标镜像可用性与机型兼容性，彻底杜绝配额回退分支删旧盘后因镜像无效导致永久丢盘
            var targetImgRes = computeClient.getImage(GetImageRequest.builder().imageId(targetImageId).build());
            if (targetImgRes == null || targetImgRes.getImage() == null
                    || targetImgRes.getImage().getLifecycleState() != Image.LifecycleState.Available) {
                throw new IllegalArgumentException("目标镜像不可用或状态异常，已拒绝执行系统重装");
            }
            if (customImageOcid != null && !customImageOcid.trim().isEmpty() && shape != null && !shape.isBlank()) {
                try {
                    computeClient.getImageShapeCompatibilityEntry(
                            GetImageShapeCompatibilityEntryRequest.builder()
                                    .imageId(targetImageId)
                                    .shapeName(shape)
                                    .build()
                    );
                } catch (BmcException compatEx) {
                    if (compatEx.getStatusCode() == 404 || compatEx.getStatusCode() == 400) {
                        throw new IllegalArgumentException("指定的自定义镜像与当前实例架构 [" + shape + "] 不兼容，已拒绝执行系统重装");
                    }
                    throw compatEx;
                }
            }

            Map<String, String> updatedMetadata = new HashMap<>();
            if (instanceInfo.getInstance().getMetadata() != null) {
                updatedMetadata.putAll(instanceInfo.getInstance().getMetadata());
            }

            boolean hasPwdReinstall = (rootPwd != null && !rootPwd.trim().isEmpty() && !"null".equalsIgnoreCase(rootPwd.trim()));
            boolean hasSshReinstall = isValidSshPublicKey(sshKey);

            if (hasSshReinstall) {
                updatedMetadata.put("ssh_authorized_keys", sshKey.trim());
            } else {
                updatedMetadata.remove("ssh_authorized_keys");
            }

            // 🔒 【重装逻辑修复】无论是否注入新密码，始终重新生成并覆盖 user_data，防止 OCI 继承原机器旧 user_data 中的密码注入脚本
            updatedMetadata.put("user_data", buildCloudInitUserData(hasPwdReinstall ? rootPwd : null, hasSshReinstall ? sshKey : null, accountName));

            Map<String, String> freeformTags = new HashMap<>();
            if (instanceInfo.getInstance().getFreeformTags() != null) {
                freeformTags.putAll(instanceInfo.getInstance().getFreeformTags());
            }
            // 🔒 【敏感信息泄漏修复】清除历史遗留的明文密码标签，且不再写入新的明文密码标签。
            freeformTags.remove("root_password");

            String cleanDisplayName = null;
            if (instanceName != null && !instanceName.trim().isEmpty()) {
                cleanDisplayName = TelegramBotService.cleanInstanceDisplayName(instanceName, accountName);
            }

            UpdateInstanceDetails.Builder detailsBuilder = UpdateInstanceDetails.builder()
                    .sourceDetails(UpdateInstanceSourceViaImageDetails.builder()
                            .imageId(targetImageId)
                            .isPreserveBootVolumeEnabled(false)
                            .build())
                    .metadata(updatedMetadata)
                    .freeformTags(freeformTags.isEmpty() ? null : freeformTags);

            if (cleanDisplayName != null && !cleanDisplayName.isEmpty()) {
                detailsBuilder.displayName(cleanDisplayName);
            }

            // 🔒 租户级互斥锁覆盖完整重装与配额回退临界区：防止同租户并发重装在旧卷刚删除释放 200GB 配额的窗口期抢占额度导致丢盘！
            Object tenancyLock = OciCloudService.TENANCY_LOCKS.computeIfAbsent(accConfig.getTenancy(), k -> new Object());
            synchronized (tenancyLock) {
                try {
                    computeClient.updateInstance(UpdateInstanceRequest.builder()
                            .instanceId(ocid)
                            .updateInstanceDetails(detailsBuilder.build())
                            .build());
                    return true;
                } catch (com.oracle.bmc.model.BmcException e) {
                    if (e.getStatusCode() == 400 && e.getMessage() != null && e.getMessage().contains("QuotaExceeded")) {
                        System.out.println("⚠️ [重装配额触顶] 检测到 UpdateInstance 触发 bootVolumeQuota 配额限制。正在执行【先解绑销毁旧卷-释放配额-后建新卷挂载】避让重装流程...");
                        return reinstallInstanceFallback(ocid, accountName, cleanDisplayName, targetImageId, updatedMetadata, freeformTags, instanceInfo.getInstance());
                    }
                    throw e;
                }
            }
        } finally {
            REINSTALLING_INSTANCES.remove(ocid);
        }
    }

    private boolean reinstallInstanceFallback(String ocid, String accountName, String instanceName, String targetImageId, Map<String, String> updatedMetadata, Map<String, String> freeformTags, Instance instance) throws Exception {
        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        var clients = clientManager.getOciClients(accountName);
        String region = OciRegionUtil.getRegionForResource(ocid);
        var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();
        var blockClient = region != null ? clients.getBlockstorageClientForRegion(region) : clients.getBlockstorage();

        String ad = instance.getAvailabilityDomain();
        String tenancy = accConfig.getTenancy();
        String compId = (instance.getCompartmentId() != null && !instance.getCompartmentId().isBlank())
                ? instance.getCompartmentId() : tenancy;

        // 1. Soft Stop Instance
        System.out.println("⏳ [配额避让重装] 正在软关机原实例以保留机器容量: " + ocid);
        try {
            computeClient.instanceAction(InstanceActionRequest.builder()
                    .instanceId(ocid)
                    .action("SOFTSTOP")
                    .build());
        } catch (Exception e) {
            System.err.println("关机指令发送异常: " + e.getMessage());
        }

        // 等待关机完成 (最多等待3分钟；遇到瞬时网络抖动继续轮询，绝不吞错提前跳出)
        long stopStart = System.currentTimeMillis();
        boolean stopped = false;
        while (System.currentTimeMillis() - stopStart < 180000) {
            Thread.sleep(5000);
            try {
                var instState = computeClient.getInstance(GetInstanceRequest.builder().instanceId(ocid).build()).getInstance().getLifecycleState();
                if (instState == Instance.LifecycleState.Stopped) {
                    stopped = true;
                    break;
                }
                if (instState == Instance.LifecycleState.Terminated || instState == Instance.LifecycleState.Terminating) {
                    throw new RuntimeException("实例已处于终止状态，重装中止！");
                }
            } catch (BmcException e) {
                if (e.getStatusCode() == 404) {
                    throw new RuntimeException("未找到目标实例 (404)，重装中止！");
                }
            } catch (RuntimeException re) {
                if (re.getMessage() != null && re.getMessage().contains("重装中止")) throw re;
            } catch (Exception ignored) {
                // 瞬时网络波动继续轮询直至关机或超时
            }
        }
        if (!stopped) {
            System.err.println("实例未能成功关机，无法分离引导卷！");
            throw new RuntimeException("实例未能成功关机，重装中止！");
        }

        // 2. 找到当前挂载的引导卷并分离
        var attachmentsRes = computeClient.listBootVolumeAttachments(
                ListBootVolumeAttachmentsRequest.builder()
                        .availabilityDomain(ad)
                        .compartmentId(compId)
                        .instanceId(ocid)
                        .build()
        );
        BootVolumeAttachment currentAttachment = null;
        for (var att : attachmentsRes.getItems()) {
            if (att.getLifecycleState() != BootVolumeAttachment.LifecycleState.Detached) {
                currentAttachment = att;
                break;
            }
        }

        if (currentAttachment == null) {
            throw new RuntimeException("未能找到该实例挂载的活动引导卷！");
        }

        String oldBootVolumeId = currentAttachment.getBootVolumeId();
        String attachmentId = currentAttachment.getId();

        System.out.println("⏳ [配额避让重装] 正在分离旧引导卷: " + oldBootVolumeId);
        computeClient.detachBootVolume(DetachBootVolumeRequest.builder()
                .bootVolumeAttachmentId(attachmentId)
                .build());
        
        // 等待分离完成 (最多等待2分钟；必须确切验证已分离或404后才允许删除旧卷，绝不因异常跳出误删挂载盘！)
        long detachStart = System.currentTimeMillis();
        boolean detached = false;
        while (System.currentTimeMillis() - detachStart < 120000) {
            Thread.sleep(5000);
            try {
                var attState = computeClient.getBootVolumeAttachment(
                    com.oracle.bmc.core.requests.GetBootVolumeAttachmentRequest.builder().bootVolumeAttachmentId(attachmentId).build()
                ).getBootVolumeAttachment().getLifecycleState();
                if (attState == BootVolumeAttachment.LifecycleState.Detached) {
                    detached = true;
                    break;
                }
            } catch (BmcException e) {
                if (e.getStatusCode() == 404) {
                    detached = true;
                    break;
                }
            } catch (Exception ignored) {
                // 瞬时网络异常继续轮询，严禁 break 后直接删盘！
            }
        }
        if (!detached) {
            throw new RuntimeException("等待旧引导卷分离超时或状态未确认，为防止损坏数据已安全中止删盘与重装流程！");
        }

        // 3. 删除旧引导卷以释放 200GB 免费配额
        System.out.println("⏳ [配额避让重装] 正在删除旧引导卷释放配额...");
        try {
            blockClient.deleteBootVolume(DeleteBootVolumeRequest.builder()
                    .bootVolumeId(oldBootVolumeId)
                    .build());
            bootVolumeService.invalidateBootVolumeCache(accountName);
        } catch (Exception e) {
            System.err.println("删除旧引导卷失败: " + e.getMessage());
            throw new RuntimeException("未能删除旧引导卷，配额无法释放，重装中止！");
        }
        
        // 额外等待5秒让 OCI 结算配额
        Thread.sleep(5000);

        // 4. 使用 UpdateInstance 从目标镜像创建并挂载新引导卷
        System.out.println("⏳ [配额避让重装] 配额已释放，正在调用 UpdateInstance 从新镜像创建并挂载新引导卷...");
        String finalName = (instanceName != null && !instanceName.trim().isEmpty()) ? instanceName.trim() : instance.getDisplayName();
        
        try {
            computeClient.updateInstance(UpdateInstanceRequest.builder()
                    .instanceId(ocid)
                    .updateInstanceDetails(UpdateInstanceDetails.builder()
                            .displayName(finalName)
                            .metadata(updatedMetadata)
                            .freeformTags(freeformTags != null && !freeformTags.isEmpty() ? freeformTags : null)
                            .sourceDetails(UpdateInstanceSourceViaImageDetails.builder()
                                    .imageId(targetImageId)
                                    .isPreserveBootVolumeEnabled(true) // 旧卷已手动删除，此处设为true防止API去尝试删除不存在的卷
                                    .build())
                            .build())
                    .build());
        } catch (Exception e) {
            System.err.println("重新挂载新引导卷失败: " + e.getMessage());
            throw new RuntimeException("避让重装失败: 无法创建并挂载新引导卷！");
        }

        // 等待新卷挂载完成 (最多等待2分钟)
        long volWaitStart = System.currentTimeMillis();
        while (System.currentTimeMillis() - volWaitStart < 120000) {
            Thread.sleep(5000);
            try {
                var attRes = computeClient.listBootVolumeAttachments(
                        ListBootVolumeAttachmentsRequest.builder()
                                .availabilityDomain(ad)
                                .compartmentId(compId)
                                .instanceId(ocid)
                                .build()
                );
                boolean attached = false;
                for (var a : attRes.getItems()) {
                    if (a.getLifecycleState() == BootVolumeAttachment.LifecycleState.Attached) {
                        attached = true;
                        break;
                    }
                }
                if (attached) {
                    break;
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        // 5. 重新开机
        System.out.println("⏳ [配额避让重装] 挂载完成，正在开机...");
        computeClient.instanceAction(InstanceActionRequest.builder()
                .instanceId(ocid)
                .action("START")
                .build());

        System.out.println("🚀 [配额避让重装] 成功通过【无损机器-替换磁盘】方式重装实例，OCID: " + ocid);
        return true;
    }

    public String getInstanceStatus(String ocid, String accountName) {
        try {
            var clients = clientManager.getOciClients(accountName);
            String region = OciRegionUtil.getRegionForResource(ocid);
            var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();
            var instanceInfo = computeClient.getInstance(GetInstanceRequest.builder().instanceId(ocid).build());
            return instanceInfo.getInstance().getLifecycleState().getValue();
        } catch (Exception e) {
            System.err.println("获取实例状态失败: " + e.getMessage());
            return "UNKNOWN";
        }
    }

    /**
     * 🔄 全量同步单台机器的所有显示信息：
     * 包括：最新名称(displayName)、电源生命周期状态(state)、机型架构(shape)、CPU核数(cpu)、
     * 内存容量(memory)、引导卷硬盘大小(disk)、区域(region)、公网IP(及500M穿透)、IP归属地(location)、IPv6。
     * 若实例在云端已不存在(404)或处于已终止(TERMINATED)状态，则自动从缓存与持久化中清理。
     */
    public Map<String, String> refreshSingleInstance(String ocid, String accountName) throws Exception {
        if (ocid == null || ocid.isBlank()) {
            throw new IllegalArgumentException("缺少实例 OCID");
        }

        if (accountName == null || accountName.isBlank()) {
            Map<String, String> cached = OciCloudService.INSTANCE_CACHE.get(ocid);
            if (cached != null && cached.get("account") != null && !cached.get("account").isBlank()) {
                accountName = cached.get("account");
            }
        }

        if (accountName == null || accountName.isBlank()) {
            for (String acc : ociProperties.getAccounts().keySet()) {
                try {
                    String reg = OciRegionUtil.getRegionForResource(ocid);
                    var clients = clientManager.getOciClients(acc);
                    var cc = reg != null ? clients.getComputeClientForRegion(reg) : clients.getCompute();
                    var res = cc.getInstance(GetInstanceRequest.builder().instanceId(ocid).build());
                    if (res != null && res.getInstance() != null) {
                        accountName = acc;
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }

        if (accountName == null || accountName.isBlank()) {
            throw new IllegalArgumentException("未找到该实例归属的云账号，请先执行全量同步");
        }

        OciProperties.Account accConfig = ociProperties.getAccounts().get(accountName);
        if (accConfig == null) {
            throw new IllegalArgumentException("云账号不存在: " + accountName);
        }

        var clients = clientManager.getOciClients(accountName);
        String region = OciRegionUtil.getRegionForResource(ocid);
        if (region == null || region.isBlank()) {
            Map<String, String> cached = OciCloudService.INSTANCE_CACHE.get(ocid);
            if (cached != null) {
                region = cached.get("region");
            }
        }
        var computeClient = region != null ? clients.getComputeClientForRegion(region) : clients.getCompute();

        Instance instance = null;
        try {
            var instRes = computeClient.getInstance(GetInstanceRequest.builder().instanceId(ocid).build());
            instance = instRes.getInstance();
        } catch (BmcException e) {
            if (e.getStatusCode() == 404) {
                // 甲骨文云端已删除该实例
                OciCloudService.INSTANCE_CACHE.remove(ocid);
                redisTemplate.delete("oci:ip:" + ocid);
                redisTemplate.delete("oci:ipv6:" + ocid);
                if (ScheduledTasksService.getInstance() != null) {
                    ScheduledTasksService.getInstance().saveSystemCache();
                }
                Map<String, String> terminatedInfo = new HashMap<>();
                terminatedInfo.put("ocid", ocid);
                terminatedInfo.put("state", "TERMINATED");
                terminatedInfo.put("terminated", "true");
                return terminatedInfo;
            }
            throw e;
        }

        if (instance.getLifecycleState() == Instance.LifecycleState.Terminated) {
            OciCloudService.INSTANCE_CACHE.remove(ocid);
            redisTemplate.delete("oci:ip:" + ocid);
            redisTemplate.delete("oci:ipv6:" + ocid);
            if (ScheduledTasksService.getInstance() != null) {
                ScheduledTasksService.getInstance().saveSystemCache();
            }
            Map<String, String> terminatedInfo = new HashMap<>();
            terminatedInfo.put("ocid", ocid);
            terminatedInfo.put("state", "TERMINATED");
            terminatedInfo.put("terminated", "true");
            return terminatedInfo;
        }

        Map<String, String> oldInfo = OciCloudService.INSTANCE_CACHE.get(ocid);
        Map<String, String> info = (oldInfo != null) ? new HashMap<>(oldInfo) : new HashMap<>();

        info.put("account", accountName);
        info.put("name", "[" + accountName + "] " + instance.getDisplayName());
        info.put("state", instance.getLifecycleState().getValue());
        info.put("shape", instance.getShape());
        if (instance.getCompartmentId() != null && !instance.getCompartmentId().isBlank()) {
            info.put("compartmentId", instance.getCompartmentId());
        }
        if (region != null && !region.isBlank()) {
            info.put("region", region);
        }

        if (instance.getShapeConfig() != null) {
            Float cpu = instance.getShapeConfig().getOcpus();
            Float mem = instance.getShapeConfig().getMemoryInGBs();
            if (cpu != null) {
                info.put("cpu", String.valueOf(cpu));
            }
            if (mem != null) {
                info.put("memory", String.valueOf(mem));
            }
        }

        // 同步引导卷大小 (disk)
        try {
            String ad = instance.getAvailabilityDomain();
            String compId = (instance.getCompartmentId() != null && !instance.getCompartmentId().isBlank())
                    ? instance.getCompartmentId() : accConfig.getTenancy();
            var attListReq = ListBootVolumeAttachmentsRequest.builder()
                    .availabilityDomain(ad)
                    .compartmentId(compId)
                    .instanceId(ocid)
                    .build();
            var attRes = computeClient.listBootVolumeAttachments(attListReq);
            String bvId = null;
            for (var att : attRes.getItems()) {
                if (att.getLifecycleState() != BootVolumeAttachment.LifecycleState.Detached) {
                    bvId = att.getBootVolumeId();
                    break;
                }
            }
            if (bvId != null) {
                var blockClient = region != null ? clients.getBlockstorageClientForRegion(region) : clients.getBlockstorage();
                var bv = blockClient.getBootVolume(GetBootVolumeRequest.builder().bootVolumeId(bvId).build()).getBootVolume();
                if (bv != null && bv.getSizeInGBs() != null) {
                    info.put("disk", String.valueOf(bv.getSizeInGBs()));
                }
            }
        } catch (Exception e) {
            System.err.println("同步单机引导卷大小异常 [" + ocid + "]: " + e.getMessage());
        }

        // 同步公网 IP
        String state = info.get("state");
        String ip;
        if ("RUNNING".equalsIgnoreCase(state)) {
            redisTemplate.delete("oci:ip:" + ocid);
            ip = networkService.getInstanceIp(ocid, accountName, true);
        } else {
            ip = networkService.getInstanceIp(ocid, accountName, true);
        }
        if (ip == null || ip.contains("无私网IP") || ip.contains("关机") || ip.contains("未分配")) {
            ip = "未分配 IP";
        }
        info.put("ip", ip);
        info.put("location", networkService.getInstanceLocation(ip, region));
        info.put("is500m", (ip != null && (ip.contains("(NAT)") || ip.contains("500M") || ip.contains("(原)"))) ? "true" : "false");

        // 同步 IPv6
        String ipv6 = networkService.getInstanceIpv6(ocid, accountName, true);
        if (ipv6 != null && !ipv6.isBlank()) {
            info.put("ipv6", ipv6);
        } else {
            info.remove("ipv6");
            redisTemplate.delete("oci:ipv6:" + ocid);
        }

        OciCloudService.INSTANCE_CACHE.put(ocid, info);

        if (ScheduledTasksService.getInstance() != null) {
            ScheduledTasksService.getInstance().saveSystemCache();
        }

        return info;
    }

    /**
     * Web 与 Telegram 共用的系统重装后台跟进与旧引导卷自动回收任务。
     */
    public void startReinstallMonitor(String ocid, String account, String instanceName, String rootPwd, String oldBootVolumeId, boolean sendInitialNotice) {
        String safePwd = (rootPwd != null) ? rootPwd.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : "";
        String safeInstance = (instanceName != null) ? instanceName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : "";
        String safeAcc = (account != null) ? account.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : "";

        if (sendInitialNotice && notificationService != null) {
            String pwdInfo = (rootPwd != null && !rootPwd.isEmpty())
                    ? "🔑 临时密码: <tg-spoiler><code>" + safePwd + "</code></tg-spoiler>"
                    : "🔑 登录方式: SSH 密钥";
            notificationService.send("系统重装任务开始",
                    "⏳ <b>系统重装任务开始</b>\n\n实例: <code>" + safeInstance + "</code>\n账号: <code>" + safeAcc + "</code>\n" + pwdInfo + "\n\n正在替换引导卷，将在后台自动跟进开机状态...",
                    "INFO", "INSTANCE");
        }
        scheduleReinstallMonitorTask(ocid, account, instanceName, rootPwd, oldBootVolumeId, 0);
    }

    private void scheduleReinstallMonitorTask(String ocid, String account, String instanceName, String rootPwd, String oldBootVolumeId, int attempt) {
        String safePwd = (rootPwd != null) ? rootPwd.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : "";
        String safeInstance = (instanceName != null) ? instanceName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : "";

        if (attempt >= 40) {
            if (notificationService != null) {
                notificationService.send("系统重装监控超时",
                        "💿 ⚠️ <b>系统重装监控超时</b>\n\n实例: <code>" + safeInstance + "</code>\n状态: 超过20分钟未能启动，请前往 OCI 控制台检查机器是否卡住。",
                        "WARNING", "INSTANCE");
            }
            return;
        }

        reinstallMonitorScheduler.schedule(() -> {
            try {
                String state = getInstanceStatus(ocid, account);
                if ("RUNNING".equalsIgnoreCase(state)) {
                    try { Thread.sleep(8000); } catch (InterruptedException ignored) {}
                    String newIp = networkService.getInstanceIp(ocid, account, true);
                    if (newIp == null || newIp.contains("无") || newIp.contains("未")) {
                        newIp = "分配较慢，请稍后在面板查看";
                    }
                    String safeIp = newIp.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");

                    String successPwdInfo = (rootPwd != null && !rootPwd.isEmpty())
                            ? "🔑 Root 密码: <tg-spoiler><code>" + safePwd + "</code></tg-spoiler>"
                            : "🔑 登录方式: SSH 密钥";

                    String volumeCleanupInfo = "";
                    if (oldBootVolumeId != null && !oldBootVolumeId.isEmpty()) {
                        boolean deleted = bootVolumeService.deleteBootVolume(oldBootVolumeId, account);
                        if (deleted) {
                            volumeCleanupInfo = "\n\n♻️ <b>存储空间自动释放</b>\n换下来的旧引导卷已自动为您成功销毁。";
                        } else {
                            volumeCleanupInfo = "\n\n⚠️ <b>存储空间释放失败</b>\n尝试销毁旧引导卷失败，这可能是由于甲骨文尚未完全解除其占用状态，请稍后在面板手动清理闲置引导卷。";
                        }
                    }

                    try {
                        refreshSingleInstance(ocid, account);
                    } catch (Exception ignored) {}

                    if (notificationService != null) {
                        notificationService.send("系统重装成功",
                                "💿 ✅ <b>系统重装成功</b>\n\n实例: <code>" + safeInstance + "</code>\n🌐 公网 IP: <code>" + safeIp + "</code>\n状态: 引导卷替换完成，<b>已成功开机运行！</b>\n" + successPwdInfo + "\n系统已更新。" + volumeCleanupInfo,
                                "SUCCESS", "INSTANCE");
                    }
                } else {
                    if ("STOPPED".equalsIgnoreCase(state)) {
                        instanceAction(ocid, "START", account);
                    }
                    scheduleReinstallMonitorTask(ocid, account, instanceName, rootPwd, oldBootVolumeId, attempt + 1);
                }
            } catch (Exception e) {
                System.err.println("系统重装监控异常: " + e.getMessage());
                if (notificationService != null) {
                    notificationService.send("系统重装监控异常",
                            "💿 ❌ <b>系统重装监控异常</b>\n\n实例: <code>" + safeInstance + "</code>\n请到网页后台或服务日志查看详情。",
                            "DANGER", "INSTANCE");
                }
            }
        }, 30, TimeUnit.SECONDS);
    }
}

