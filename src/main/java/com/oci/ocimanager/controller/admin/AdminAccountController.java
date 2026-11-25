package com.oci.ocimanager.controller.admin;

import com.oci.ocimanager.config.OciProperties;
import com.oci.ocimanager.entity.CreationTaskRecord;
import com.oci.ocimanager.entity.TrafficLimit;
import com.oci.ocimanager.repository.CreationTaskRepository;
import com.oci.ocimanager.repository.TrafficLimitRepository;
import com.oci.ocimanager.service.AccountYamlService;
import com.oci.ocimanager.service.AdminAuthService;
import com.oci.ocimanager.service.CreationTaskService;
import com.oci.ocimanager.service.OciCloudService;
import com.oci.ocimanager.service.ScheduledTasksService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 账号管理接口：标签(/account-tag)、改名(/account-rename)、删除(/account-delete)、导入(/import-account)。
 * YAML 结构操作统一经由 AccountYamlService。原属 AdminController，URL 保持不变。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAccountController {

    @Autowired private TrafficLimitRepository trafficLimitRepository;
    @Autowired private CreationTaskRepository creationTaskRepository;
    @Autowired private CreationTaskService creationTaskService;
    @Autowired private OciCloudService ociCloudService;
    @Autowired private OciProperties ociProperties;
    @Autowired private AccountYamlService accountYamlService;
    @Autowired private AdminAuthService adminAuthService;
    @Autowired private com.oci.ocimanager.service.OciClientFingerprintService fingerprintService;
    @Autowired private com.oci.ocimanager.service.NotificationService notificationService;

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(AdminAccountController.class.getName());

    private boolean checkAuth(HttpServletRequest request, Map<String, ?> payload) {
        return adminAuthService.checkAuth(request, payload);
    }

    @PostMapping("/account-client-identity")
    public Map<String, Object> updateClientIdentity(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "鉴权失败");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        String account = (String) payload.get("account");
        if (account == null || account.trim().isEmpty()) {
            return Map.of("success", false, "error", "账号名不能为空");
        }

        OciProperties.Account acc = ociProperties.getAccounts().get(account);
        if (acc == null) {
            String clean = account.replaceAll("[\"'\\s\\[\\]\\r\\n]", "");
            for (Map.Entry<String, OciProperties.Account> e : ociProperties.getAccounts().entrySet()) {
                if (e.getKey().replaceAll("[\"'\\s\\[\\]\\r\\n]", "").equals(clean)) {
                    acc = e.getValue();
                    account = e.getKey();
                    break;
                }
            }
        }
        if (acc == null) {
            return Map.of("success", false, "error", "账号不存在: " + account);
        }

        boolean reroll = Boolean.TRUE.equals(payload.get("reroll"));
        String clientIdentifier = (String) payload.get("clientIdentifier");
        String proxy = (String) payload.get("proxy");

        String trimmedId = null;
        if (!reroll && clientIdentifier != null) {
            trimmedId = clientIdentifier.trim();
            if (!trimmedId.isEmpty() && !accountYamlService.isSafeYamlValue(trimmedId)) {
                return Map.of("success", false, "error", "客户端标识包含不允许的字符");
            }
        }
        String trimmedProxy = null;
        if (proxy != null) {
            trimmedProxy = proxy.trim();
            if (!trimmedProxy.isEmpty()) {
                if (!accountYamlService.isSafeYamlValue(trimmedProxy)) {
                    return Map.of("success", false, "error", "代理地址包含不允许的字符");
                }
                String unsafeProxyReason = com.oci.ocimanager.service.OciClientWrapper.validateProxySafety(trimmedProxy);
                if (unsafeProxyReason != null) {
                    return Map.of("success", false, "error", unsafeProxyReason);
                }
            }
        }

        String rerollProfileName = null;
        if (reroll) {
            acc.setClientIdentifier(null);
            var newFp = fingerprintService.rerollFingerprint(account, acc);
            if (newFp != null) {
                rerollProfileName = newFp.profileName();
                if (newFp.userAgent() != null) {
                    acc.setClientIdentifier(newFp.userAgent());
                    accountYamlService.persistClientIdentifierToYaml(account, newFp.userAgent());
                }
            }
        } else if (trimmedId != null) {
            acc.setClientIdentifier(trimmedId.isEmpty() ? null : trimmedId);
            accountYamlService.persistClientIdentifierToYaml(account, trimmedId);
            if (trimmedId.isEmpty()) {
                fingerprintService.clearCache(account);
            } else {
                fingerprintService.setCustomIdentifier(account, trimmedId, acc);
            }
        }

        if (trimmedProxy != null) {
            acc.setProxy(trimmedProxy.isEmpty() ? null : trimmedProxy);
            accountYamlService.persistProxyToYaml(account, trimmedProxy);
        }

        // 清除客户端缓存，确保下次网络调用即刻采用全新独立标识与代理
        ociCloudService.clearClientCacheForAccount(account);
        var fp = ociCloudService.getClientFingerprint(account);

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        String msg = reroll 
                ? ("已成功为该账号随机切换全新的独立生态客户端指纹: " + (rerollProfileName != null ? rerollProfileName : "已生效")) 
                : "客户端指纹与网络特征已成功更新！";
        res.put("message", msg);
        if (fp != null) {
            res.put("clientProfile", fp.profileName());
            res.put("clientUserAgent", fp.userAgent());
            res.put("clientInfo", fp.opcClientInfo());
            res.put("clientLanguage", fp.acceptLanguage());
            res.put("clientAccept", fp.accept());
            res.put("clientEncoding", fp.acceptEncoding());
            res.put("clientRequestIdStyle", fp.requestIdStyle());
            res.put("clientTimeouts", fp.connectTimeoutMillis() + "ms / " + fp.readTimeoutMillis() + "ms");
            res.put("clientPhaseOffset", fp.phaseOffsetMs() + "ms");
        }
        res.put("proxy", com.oci.ocimanager.service.OciClientWrapper.redactProxy(acc.getProxy()));
        res.put("clientIdentifier", acc.getClientIdentifier() != null ? acc.getClientIdentifier() : "");
        return res;
    }

    @PostMapping("/account-tag")
    public Map<String, Object> setAccountTag(HttpServletRequest request, @RequestBody(required = false) Map<String, String> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "鉴权失败");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        String account = payload.get("account");
        String tag = payload.get("tag");
        if (account == null || account.trim().isEmpty()) {
            return Map.of("success", false, "error", "账号名不能为空");
        }
        ociCloudService.setAccountTag(account, tag);
        return Map.of("success", true, "message", "标签更新成功", "tag", tag != null ? tag.trim() : "");
    }

    @PostMapping("/account-payg")
    public Map<String, Object> setAccountPayg(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "鉴权失败");
        return Map.of("success", false, "error", "手动切换功能已取消，系统已全面启用自动智能识别");
    }

    @PostMapping("/account-rename")
    @Transactional
    public Map<String, Object> renameAccount(HttpServletRequest request, @RequestBody(required = false) Map<String, String> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        String oldAccount = payload.get("oldAccount");
        String newAccount = payload.get("newAccount");
        if (oldAccount == null || oldAccount.trim().isEmpty()) return Map.of("success", false, "error", "原账号名不能为空");
        if (newAccount == null || newAccount.trim().isEmpty()) return Map.of("success", false, "error", "新账号名不能为空");

        String cleanOld = oldAccount.replaceAll("[\"'\\s\\[\\]\\r\\n]", "");
        String cleanNew = newAccount.replaceAll("[\"'\\s\\[\\]\\r\\n]", "");

        if (cleanOld.equals(cleanNew)) return Map.of("success", false, "error", "新旧账号名相同");

        // 🔒 新账号名同样会写入 YAML 键，拒绝冒号/井号等剩余的结构性字符。
        if (!accountYamlService.isSafeYamlName(cleanNew)) {
            return Map.of("success", false, "error", "新账号名包含非法字符（不允许冒号、井号等）");
        }

        if (ociProperties.getAccounts().keySet().stream().anyMatch(k -> k.replaceAll("[\"'\\s\\[\\]\\r\\n]", "").equals(cleanNew))) {
            return Map.of("success", false, "error", "新账号名已存在");
        }

        synchronized (accountYamlService.lock()) {
            try {
                // 🔒 1. 先校验内存权威账号表是否确有该账号，杜绝未命中时先改坏 YAML 再触发 NPE！
                String matchedOldKey = null;
                for (String k : ociProperties.getAccounts().keySet()) {
                    if (AccountYamlService.normalizeAccountKey(k).equals(cleanOld)) {
                        matchedOldKey = k;
                        break;
                    }
                }
                if (matchedOldKey == null) {
                    return Map.of("success", false, "error", "未找到原账号: " + oldAccount);
                }

                // 🔒 2. 在 oci.accounts 严格缩进作用域内原子重命名 YAML 键
                File file = accountYamlService.getConfigFile();
                if (file == null || !file.exists()) {
                    return Map.of("success", false, "error", "找不到 application.yml 配置文件");
                }
                boolean renamedInYaml = accountYamlService.renameAccountInYaml(file, matchedOldKey, cleanNew);
                if (!renamedInYaml) {
                    return Map.of("success", false, "error", "YAML 中未能精准定位到账号节点 [" + oldAccount + "]");
                }

                // 3. 更新内存账号顺序表
                OciProperties.Account acc = ociProperties.getAccounts().get(matchedOldKey);
                Map<String, OciProperties.Account> newMap = new LinkedHashMap<>();
                for (Map.Entry<String, OciProperties.Account> e : ociProperties.getAccounts().entrySet()) {
                    if (e.getKey().equals(matchedOldKey)) {
                        newMap.put(cleanNew, acc);
                    } else {
                        newMap.put(e.getKey(), e.getValue());
                    }
                }
                ociProperties.setAccounts(newMap);

                // 4. 完整迁移流量缓存、熔断锁 (_meltdown)、标签、PAYG、账单与封禁状态缓存
                if (ScheduledTasksService.getInstance() != null) {
                    ScheduledTasksService.getInstance().migrateAccountRecords(matchedOldKey, cleanNew);
                } else {
                    Double oldTraffic = ScheduledTasksService.TRAFFIC_CACHE.remove(matchedOldKey);
                    if (oldTraffic != null) ScheduledTasksService.TRAFFIC_CACHE.put(cleanNew, oldTraffic);
                    String oldUpdateTime = ScheduledTasksService.UPDATE_TIME_CACHE.remove(matchedOldKey);
                    if (oldUpdateTime != null) ScheduledTasksService.UPDATE_TIME_CACHE.put(cleanNew, oldUpdateTime);
                    String oldMelt = ScheduledTasksService.WARNING_SENT_RECORD.remove(matchedOldKey + "_meltdown");
                    if (oldMelt != null) ScheduledTasksService.WARNING_SENT_RECORD.put(cleanNew + "_meltdown", oldMelt);
                }
                ociCloudService.migrateAccountBillingState(matchedOldKey, cleanNew);

                // 5. 迁移数据库流量限额
                if (trafficLimitRepository != null) {
                    Optional<TrafficLimit> tlOpt = trafficLimitRepository.findById(matchedOldKey);
                    if (tlOpt.isPresent()) {
                        TrafficLimit tl = tlOpt.get();
                        trafficLimitRepository.deleteById(matchedOldKey);
                        TrafficLimit migrated = new TrafficLimit();
                        migrated.setAccountName(cleanNew);
                        migrated.setLimitGb(tl.getLimitGb());
                        migrated.setLastWarningMonth(tl.getLastWarningMonth());
                        trafficLimitRepository.save(migrated);
                    }
                }

                // 6. 同步更新运行中抢机线程内存对象与数据库任务记录
                if (creationTaskService != null) {
                    creationTaskService.onAccountRenamed(matchedOldKey, cleanNew);
                    if (!matchedOldKey.equals(cleanOld)) {
                        creationTaskService.onAccountRenamed(cleanOld, cleanNew);
                    }
                }

                // 7. 同步迁移实例缓存中的所属账号与展示前缀，防止轮询清理或显示断档
                for (Map<String, String> inst : OciCloudService.INSTANCE_CACHE.values()) {
                    if (inst != null && matchedOldKey.equals(inst.get("account"))) {
                        inst.put("account", cleanNew);
                        String rawName = inst.get("name");
                        if (rawName != null && rawName.startsWith("[" + matchedOldKey + "] ")) {
                            inst.put("name", "[" + cleanNew + "] " + rawName.substring(("[" + matchedOldKey + "] ").length()));
                        }
                    }
                }

                ociCloudService.clearClientCacheForAccount(matchedOldKey);
                if (ScheduledTasksService.getInstance() != null) {
                    ScheduledTasksService.getInstance().saveSystemCache();
                }
                return Map.of("success", true);

            } catch (Exception e) {
                LOG.log(java.util.logging.Level.WARNING, "账号重命名失败", e);
                return com.oci.ocimanager.controller.ApiErrors.fail("重命名失败", e);
            }
        }
    }

    @PostMapping("/account-delete")
    @Transactional
    public Map<String, Object> deleteAccount(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        Object rawAccObj = payload.get("account");
        String rawAccount = rawAccObj != null ? String.valueOf(rawAccObj) : null;
        if (rawAccount == null || rawAccount.trim().isEmpty()) return Map.of("success", false, "error", "账号名不能为空");

        Map<String, Object> stepUp = adminAuthService.verifyOrRequireStepUp(request, payload);
        if (!Boolean.TRUE.equals(stepUp.get("success"))) {
            return stepUp;
        }

        String cleanTarget = AccountYamlService.normalizeAccountKey(rawAccount);
        if (cleanTarget.isEmpty()) {
            return Map.of("success", false, "error", "无效的账号名称");
        }

        synchronized (accountYamlService.lock()) {
            try {
                // 🔒 1. 先检查内存账号表是否确有该账号，杜绝未命中时误改写 application.yml！
                List<String> keysToRemove = new ArrayList<>();
                for (String k : ociProperties.getAccounts().keySet()) {
                    if (AccountYamlService.normalizeAccountKey(k).equals(cleanTarget)) {
                        keysToRemove.add(k);
                    }
                }
                if (keysToRemove.isEmpty()) {
                    return Map.of("success", false, "error", "未找到账号: " + rawAccount);
                }

                File file = accountYamlService.getConfigFile();
                if (file != null && file.exists()) {
                    accountYamlService.deleteAccountFromYaml(file, cleanTarget);
                }

                Map<String, OciProperties.Account> updatedAccounts = new LinkedHashMap<>(ociProperties.getAccounts());
                List<String> candidateKeyFiles = new ArrayList<>();
                for (String key : keysToRemove) {
                    OciProperties.Account acc = updatedAccounts.remove(key);
                    if (acc != null && acc.getKeyFile() != null) {
                        candidateKeyFiles.add(acc.getKeyFile());
                    }
                    if (ScheduledTasksService.getInstance() != null) {
                        ScheduledTasksService.getInstance().purgeAccountRecords(key);
                    } else {
                        ScheduledTasksService.TRAFFIC_CACHE.remove(key);
                        ScheduledTasksService.UPDATE_TIME_CACHE.remove(key);
                        ScheduledTasksService.WARNING_SENT_RECORD.remove(key);
                        ScheduledTasksService.WARNING_SENT_RECORD.remove(key + "_meltdown");
                    }
                    ociCloudService.purgeAccountBillingState(key);
                    ociCloudService.clearClientCacheForAccount(key);
                    if (trafficLimitRepository != null) trafficLimitRepository.deleteById(key);
                }
                for (String kf : candidateKeyFiles) {
                    File keyFile = new File(kf);
                    if (keyFile.exists()
                            && !isKeyFileReferencedByAnyAccount(keyFile, updatedAccounts)
                            && isSafeKeyFileToDelete(keyFile, file)) {
                        keyFile.delete();
                    }
                }
                ociProperties.setAccounts(updatedAccounts);
                if (ScheduledTasksService.getInstance() != null) {
                    ScheduledTasksService.getInstance().purgeAccountRecords(cleanTarget);
                }
                ociCloudService.purgeAccountBillingState(cleanTarget);

                // 强力停止并清理数据库中属于该账户的任务记录
                if (creationTaskService != null) {
                    List<CreationTaskRecord> allTasks = creationTaskService.getAllTasks();
                    for (CreationTaskRecord t : allTasks) {
                        if (t.getAccount() != null && AccountYamlService.normalizeAccountKey(t.getAccount()).equals(cleanTarget)) {
                            creationTaskService.stopTask(t.getId());
                        }
                    }
                }
                if (creationTaskRepository != null) {
                    List<CreationTaskRecord> accountTasks = creationTaskRepository.findAll().stream()
                            .filter(t -> t.getAccount() != null && AccountYamlService.normalizeAccountKey(t.getAccount()).equals(cleanTarget))
                            .collect(Collectors.toList());
                    creationTaskRepository.deleteAll(accountTasks);
                }

                // 清理内存中属于该账户的实例缓存及熔断跟踪记录
                List<String> accountOcids = new ArrayList<>();
                Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
                if (instances != null) {
                    for (Map.Entry<String, Map<String, String>> entry : instances.entrySet()) {
                        String accVal = entry.getValue().get("account");
                        if (accVal != null && AccountYamlService.normalizeAccountKey(accVal).equals(cleanTarget)) {
                            accountOcids.add(entry.getKey());
                        }
                    }
                    for (String ocid : accountOcids) {
                        instances.remove(ocid);
                        ScheduledTasksService.MELTDOWN_STOPPED_OCIDS.remove(ocid);
                    }
                }

                if (ScheduledTasksService.getInstance() != null) {
                    ScheduledTasksService.getInstance().saveSystemCache();
                }

                CompletableFuture.runAsync(() -> {
                    try {
                        ociCloudService.updateInstancesCache(true);
                        if (ScheduledTasksService.getInstance() != null) {
                            ScheduledTasksService.getInstance().saveSystemCache();
                        }
                    } catch (Exception e) {
                        System.err.println("账户抹除后缓存同步异常: " + e.getMessage());
                    }
                });

                return Map.of("success", true);
            } catch (Exception e) {
                return com.oci.ocimanager.controller.ApiErrors.fail("账号清理失败", e);
            }
        }
    }

    @PostMapping("/import-account")
    public Map<String, Object> importAccount(HttpServletRequest request, @RequestBody(required = false) Map<String, String> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        
        try {
            String rawAccountName = payload.get("accountName");
            String configText = payload.get("configText");
            String privateKeyText = payload.get("privateKeyText");

            if (rawAccountName == null || rawAccountName.trim().isEmpty()) return Map.of("success", false, "error", "账号别名不能为空");
            if (configText == null || configText.trim().isEmpty()) return Map.of("success", false, "error", "配置内容不能为空");
            if (privateKeyText == null || privateKeyText.trim().isEmpty()) return Map.of("success", false, "error", "私钥内容不能为空");
            if (configText.length() > 16384) return Map.of("success", false, "error", "配置内容超出长度限制 (最大 16KB)");
            if (privateKeyText.length() > 32768) return Map.of("success", false, "error", "私钥文件超出长度限制 (最大 32KB)");

            String cleanAccountName = rawAccountName.replaceAll("[\"']", "").trim();

            // 🔒 【安全加固】阻断 YAML 注入：账号别名一旦包含换行/引号/冒号/井号/方括号等元字符，
            // 就可能改写 application.yml 结构（如注入恶意 key-file 指向任意文件，再借 /account-delete 删除）。
            if (!accountYamlService.isSafeYamlName(cleanAccountName)) {
                return Map.of("success", false, "error", "账号别名包含非法字符（不允许换行、引号、冒号、井号或方括号）");
            }

            String user = accountYamlService.extractIniValue(configText, "user");
            String fingerprint = accountYamlService.extractIniValue(configText, "fingerprint");
            String tenancy = accountYamlService.extractIniValue(configText, "tenancy");
            String region = accountYamlService.extractIniValue(configText, "region");

            if (user == null || fingerprint == null || tenancy == null || region == null) {
                return Map.of("success", false, "error", "无法从配置中解析出完整的 OCI 凭据，请检查是否完整粘贴了 [DEFAULT] 块内容");
            }

            // 🔒 凭据字段会被写入带引号的 YAML 值，含引号/换行/反斜杠即可越权改写配置，直接拒绝。
            if (!accountYamlService.isSafeYamlValue(user) || !accountYamlService.isSafeYamlValue(fingerprint) || !accountYamlService.isSafeYamlValue(tenancy) || !accountYamlService.isSafeYamlValue(region)) {
                return Map.of("success", false, "error", "OCI 凭据字段包含非法字符，导入已阻止");
            }

            String basePath = new File("/root/ocibox").exists() ? "/root/ocibox" : (new File("/root/oci-personal").exists() ? "/root/oci-personal" : (new File("/root/oci-manager").exists() ? "/root/oci-manager" : "."));
            String keysPath = basePath + "/keys";
            new File(keysPath).mkdirs();
            
            String englishFileName = "key_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            String keyFilePath = keysPath + "/" + englishFileName + ".pem";
            
            // 🔒 keys 目录收紧为 700，避免同机其它用户读取私钥。
            try {
                Files.setPosixFilePermissions(Paths.get(keysPath), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            } catch (Exception ignored) {}

            String cleanPrivateKey = privateKeyText.trim().replace("\r\n", "\n");
            if (cleanPrivateKey.indexOf('\0') >= 0
                    || !cleanPrivateKey.contains("-----BEGIN ")
                    || !cleanPrivateKey.contains("PRIVATE KEY-----")) {
                return Map.of("success", false, "error", "私钥格式无效，请上传或粘贴合法的 PEM 格式私钥文件");
            }
            java.nio.file.Path keyPath = Paths.get(keyFilePath);
            // 🔒 原子创建 600 权限的 OCI 私钥文件，消除先写文件再改权限之间的默认 umask (0644) 全局可读竞态窗口
            byte[] keyBytes = cleanPrivateKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                java.nio.file.attribute.FileAttribute<Set<java.nio.file.attribute.PosixFilePermission>> attr =
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(
                        keyPath,
                        java.util.EnumSet.of(
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                                java.nio.file.StandardOpenOption.WRITE),
                        attr)) {
                    channel.write(java.nio.ByteBuffer.wrap(keyBytes));
                }
                Files.setPosixFilePermissions(keyPath, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                Files.write(keyPath, keyBytes);
                File f = keyPath.toFile();
                f.setReadable(false, false);
                f.setWritable(false, false);
                f.setExecutable(false, false);
                f.setReadable(true, true);
                f.setWritable(true, true);
            }

            OciProperties.Account newAcc = new OciProperties.Account();
            newAcc.setUser(user);
            newAcc.setFingerprint(fingerprint);
            newAcc.setTenancy(tenancy);
            newAcc.setRegion(region);
            newAcc.setKeyFile(keyFilePath);

            synchronized (accountYamlService.lock()) {
                File ymlFile = accountYamlService.getOrCreateWritableConfigFile();
                List<String> oldKeyFilesToCleanup = new ArrayList<>();
                Map<String, OciProperties.Account> currentAccounts = new LinkedHashMap<>(ociProperties.getAccounts());
                String preservedProxy = null;
                String preservedClientId = null;
                String preservedRegTime = null;
                for (String k : new ArrayList<>(currentAccounts.keySet())) {
                    if (AccountYamlService.normalizeAccountKey(k).equals(cleanAccountName)) {
                        OciProperties.Account prevAcc = currentAccounts.remove(k);
                        if (prevAcc != null) {
                            if (prevAcc.getKeyFile() != null && !prevAcc.getKeyFile().equals(keyFilePath)) {
                                oldKeyFilesToCleanup.add(prevAcc.getKeyFile());
                            }
                            if (prevAcc.getProxy() != null && !prevAcc.getProxy().isBlank()) {
                                preservedProxy = prevAcc.getProxy();
                            }
                            if (prevAcc.getClientIdentifier() != null && !prevAcc.getClientIdentifier().isBlank()) {
                                preservedClientId = prevAcc.getClientIdentifier();
                            }
                            if (tenancy.equals(prevAcc.getTenancy())
                                    && prevAcc.getRegisterTime() != null
                                    && !prevAcc.getRegisterTime().isBlank()) {
                                preservedRegTime = prevAcc.getRegisterTime();
                            }
                        }
                        ociCloudService.clearClientCacheForAccount(k);
                    }
                }
                if (preservedProxy != null) newAcc.setProxy(preservedProxy);
                if (preservedClientId != null) newAcc.setClientIdentifier(preservedClientId);
                if (preservedRegTime != null) newAcc.setRegisterTime(preservedRegTime);

                try {
                    accountYamlService.upsertAccountInYaml(ymlFile, cleanAccountName, user, fingerprint, tenancy, region, keyFilePath);
                    if (preservedProxy != null) {
                        accountYamlService.persistAccountPropertyToYaml(ymlFile, cleanAccountName, "proxy", preservedProxy);
                    }
                    if (preservedClientId != null) {
                        accountYamlService.persistAccountPropertyToYaml(ymlFile, cleanAccountName, "client-identifier", preservedClientId);
                    }
                    if (preservedRegTime != null) {
                        accountYamlService.persistAccountPropertyToYaml(ymlFile, cleanAccountName, "register-time", preservedRegTime);
                    }
                } catch (Exception yamlEx) {
                    try { Files.deleteIfExists(keyPath); } catch (Exception ignored) {}
                    throw yamlEx;
                }

                currentAccounts.put(cleanAccountName, newAcc);
                ociProperties.setAccounts(accountYamlService.reorderAccounts(currentAccounts));
                ociCloudService.clearClientCacheForAccount(cleanAccountName);

                for (String oldKf : oldKeyFilesToCleanup) {
                    File oldFile = new File(oldKf);
                    if (oldFile.exists()
                            && !isKeyFileReferencedByAnyAccount(oldFile, ociProperties.getAccounts())
                            && isSafeKeyFileToDelete(oldFile, ymlFile)) {
                        oldFile.delete();
                    }
                }
            }

            CompletableFuture.runAsync(() -> {
                try {
                    ociCloudService.updateInstancesCache();
                    
                    double usage = -1.0;
                    int maxRetries = 3;
                    for (int i = 0; i < maxRetries; i++) {
                        try {
                            usage = ociCloudService.fetchTrafficInGb(cleanAccountName);
                            if (usage >= 0) break;
                        } catch (Exception apiEx) {
                            if (apiEx.getMessage() != null && apiEx.getMessage().contains("500") && apiEx.getMessage().contains("InternalError")) {
                                System.err.println("预热新账号 [" + cleanAccountName + "] 流量数据触发 500 错误，正在进行第 " + (i + 1) + " 次退避重试...");
                                if (i == maxRetries - 1) throw apiEx;
                                try { Thread.sleep(4000 * (i + 1)); } catch (InterruptedException ignored) {}
                            } else {
                                throw apiEx;
                            }
                        }
                    }

                    if (usage >= 0) {
                        if (ScheduledTasksService.getInstance() != null) {
                            String currentMonth = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
                            ScheduledTasksService.getInstance().evaluateAndRecordTrafficUsage(cleanAccountName, usage, currentMonth);
                        } else {
                            ScheduledTasksService.TRAFFIC_CACHE.put(cleanAccountName, usage);
                            ScheduledTasksService.UPDATE_TIME_CACHE.put(cleanAccountName, java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " UTC");
                        }
                    }
                } catch (Exception e) {
                    System.err.println("导入新账号预热异常 [" + cleanAccountName + "]: " + e.getMessage());
                    ScheduledTasksService.TRAFFIC_CACHE.put(cleanAccountName, -1.0);
                    ScheduledTasksService.UPDATE_TIME_CACHE.put(cleanAccountName, "暂无数据(新号接口未初始化，稍后自动重试)");
                }
            });

            notificationService.send("导入新账号", "🌟 **[后台配置更新]**\n管理员刚刚通过网页控制台导入了新账号: `" + cleanAccountName + "` 并在后台完成预热！", "INFO", "SYSTEM");

            return Map.of("success", true, "msg", "账号导入成功，已在后台自动建立预热缓存！");
        } catch (Exception e) {
            return com.oci.ocimanager.controller.ApiErrors.fail("账号导入失败", e);
        }
    }

    @PostMapping("/account/regions")
    public Map<String, Object> getAccountRegions(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "鉴权失败");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        String account = (String) payload.get("account");
        if (account == null || account.trim().isEmpty()) {
            return Map.of("success", false, "error", "缺少账号参数");
        }
        return ociCloudService.getAccountRegionInfo(account);
    }

    @PostMapping("/account/subscribe-region")
    public Map<String, Object> subscribeRegion(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "鉴权失败");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        String account = (String) payload.get("account");
        String region = (String) payload.get("region");
        if (account == null || account.trim().isEmpty() || region == null || region.trim().isEmpty()) {
            return Map.of("success", false, "error", "缺少账号或目标区域参数");
        }
        String homeRegion = (String) payload.get("homeRegion");
        Map<String, Object> res = ociCloudService.subscribeRegion(account, region, homeRegion);
        if (Boolean.TRUE.equals(res.get("success"))) {
            notificationService.send("OCI 区域扩充通知", "🌍 **[OCI 区域扩充通知]**\n管理员为账号 `" + account + "` 成功提交了新区域订阅: `" + res.get("regionName") + " (" + res.get("regionKey") + ")`！", "INFO", "SYSTEM");
        }
        return res;
    }

    private static boolean isKeyFileReferencedByAnyAccount(File targetKeyFile, Map<String, OciProperties.Account> accounts) {
        if (targetKeyFile == null || accounts == null || accounts.isEmpty()) return false;
        try {
            java.nio.file.Path targetReal = targetKeyFile.toPath().toRealPath();
            for (OciProperties.Account acc : accounts.values()) {
                if (acc == null || acc.getKeyFile() == null || acc.getKeyFile().isBlank()) continue;
                try {
                    File other = new File(acc.getKeyFile());
                    if (other.exists() && targetReal.equals(other.toPath().toRealPath())) {
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 🔒 防御纵深校验：仅允许删除严格位于本程序工作目录/配置目录专属 keys 子目录下的私钥文件 (.pem / .key)，
     * 绝不允许通过构造任意 `/somewhere/keys/foo.key` 越界删除外部目录文件！
     */
    public static boolean isSafeKeyFileToDelete(File keyFile, File configFile) {
        if (keyFile == null || !keyFile.isFile()) return false;
        try {
            java.nio.file.Path canonicalTarget = keyFile.toPath().toRealPath();
            String fileName = canonicalTarget.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!fileName.endsWith(".pem") && !fileName.endsWith(".key")) {
                return false;
            }
            java.nio.file.Path parentDir = canonicalTarget.getParent();
            if (parentDir == null || !"keys".equals(parentDir.getFileName().toString())) {
                return false;
            }

            List<java.nio.file.Path> allowedKeyDirs = new ArrayList<>();
            allowedKeyDirs.add(Paths.get(".", "keys"));
            allowedKeyDirs.add(Paths.get("/root/ocibox/keys"));
            allowedKeyDirs.add(Paths.get("/root/oci-personal/keys"));
            allowedKeyDirs.add(Paths.get("/root/oci-manager/keys"));
            if (configFile != null && configFile.getAbsoluteFile().getParentFile() != null) {
                allowedKeyDirs.add(configFile.getAbsoluteFile().getParentFile().toPath().resolve("keys"));
            }

            for (java.nio.file.Path candidate : allowedKeyDirs) {
                try {
                    if (Files.exists(candidate) && parentDir.equals(candidate.toRealPath())) {
                        return true;
                    }
                } catch (Exception ignored) {}
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}

