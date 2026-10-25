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
import com.oci.ocimanager.service.TelegramBotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    @Autowired private TelegramBotService telegramBotService;
    @Autowired private OciCloudService ociCloudService;
    @Autowired private OciProperties ociProperties;
    @Autowired private AccountYamlService accountYamlService;
    @Autowired private AdminAuthService adminAuthService;
    @Autowired private com.oci.ocimanager.service.OciClientFingerprintService fingerprintService;

    @Value("${app.admin-id}")
    private String adminId;

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(AdminAccountController.class.getName());

    private boolean checkAuth(String token) {
        return adminAuthService.checkAuth(token);
    }

    @PostMapping("/account-client-identity")
    public Map<String, Object> updateClientIdentity(@RequestBody Map<String, Object> payload) {
        if (!checkAuth((String) payload.get("token"))) return Map.of("success", false, "error", "鉴权失败");
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

        if (reroll) {
            acc.setClientIdentifier(null);
            accountYamlService.persistClientIdentifierToYaml(account, "");
            fingerprintService.rerollFingerprint(account, acc);
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
        res.put("message", reroll ? "已成功为该账号随机切换全新的专属生态客户端指纹！" : "客户端指纹与网络特征已成功更新！");
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
        res.put("proxy", acc.getProxy() != null ? acc.getProxy() : "");
        res.put("clientIdentifier", acc.getClientIdentifier() != null ? acc.getClientIdentifier() : "");
        return res;
    }

    @PostMapping("/account-tag")
    public Map<String, Object> setAccountTag(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "鉴权失败");
        String account = payload.get("account");
        String tag = payload.get("tag");
        if (account == null || account.trim().isEmpty()) {
            return Map.of("success", false, "error", "账号名不能为空");
        }
        ociCloudService.setAccountTag(account, tag);
        return Map.of("success", true, "message", "标签更新成功", "tag", tag != null ? tag.trim() : "");
    }

    @PostMapping("/account-payg")
    public Map<String, Object> setAccountPayg(@RequestBody Map<String, Object> payload) {
        if (!checkAuth((String) payload.get("token"))) return Map.of("success", false, "error", "鉴权失败");
        return Map.of("success", false, "error", "手动切换功能已取消，系统已全面启用自动智能识别");
    }

    @PostMapping("/account-rename")
    @Transactional
    public Map<String, Object> renameAccount(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "会话已过期，请重新登录");
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
                File file = accountYamlService.getConfigFile();
                
                if (file != null && file.exists()) {
                    List<String> lines = Files.readAllLines(file.toPath());
                    List<String> newLines = new ArrayList<>();
                    boolean accountFound = false;

                    for (String line : lines) {
                        if (accountYamlService.isTargetYamlKey(line, cleanOld)) {
                            int colonIndex = line.indexOf(':');
                            String beforeKey = line.substring(0, line.indexOf(line.trim()));
                            String newKeyLine = beforeKey + "\"[" + cleanNew + "]\"" + line.substring(colonIndex);
                            newLines.add(newKeyLine);
                            accountFound = true;
                        } else {
                            newLines.add(line);
                        }
                    }
                    
                    if (accountFound) {
                        Files.write(file.toPath(), newLines);
                    } else {
                        return Map.of("success", false, "error", "YAML 中未能精准定位到账号节点 [" + oldAccount + "]");
                    }
                } else {
                    return Map.of("success", false, "error", "找不到 application.yml 配置文件");
                }

                // 1. Memory update
                String matchedOldKey = null;
                for (String k : ociProperties.getAccounts().keySet()) {
                    if (k.replaceAll("[\"'\\s\\[\\]\\r\\n]", "").equals(cleanOld)) {
                        matchedOldKey = k;
                        break;
                    }
                }
                
                if (matchedOldKey != null) {
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
                }

                // Caches
                Double oldTraffic = ScheduledTasksService.TRAFFIC_CACHE.remove(matchedOldKey);
                if (oldTraffic != null) ScheduledTasksService.TRAFFIC_CACHE.put(cleanNew, oldTraffic);
                
                String oldUpdateTime = ScheduledTasksService.UPDATE_TIME_CACHE.remove(matchedOldKey);
                if (oldUpdateTime != null) ScheduledTasksService.UPDATE_TIME_CACHE.put(cleanNew, oldUpdateTime);

                // DB limits
                if (trafficLimitRepository != null) {
                    Optional<TrafficLimit> tlOpt = trafficLimitRepository.findById(matchedOldKey);
                    if (tlOpt.isPresent()) {
                        TrafficLimit tl = tlOpt.get();
                        trafficLimitRepository.deleteById(matchedOldKey);
                        tl.setAccountName(cleanNew);
                        trafficLimitRepository.save(tl);
                    }
                }

                // DB tasks
                if (creationTaskRepository != null) {
                    List<CreationTaskRecord> allTasks = creationTaskRepository.findAll();
                    for (CreationTaskRecord t : allTasks) {
                        if (t.getAccount() != null && t.getAccount().replaceAll("[\"'\\s\\[\\]\\r\\n]", "").equals(cleanOld)) {
                            t.setAccount(cleanNew);
                            creationTaskRepository.save(t);
                        }
                    }
                }

                ociCloudService.clearProviderCache();
                return Map.of("success", true);

            } catch (Exception e) {
                LOG.log(java.util.logging.Level.WARNING, "账号重命名失败", e);
                return Map.of("success", false, "error", "重命名失败: " + e.getMessage());
            }
        }
    }

    // 🌟 物理抹杀版：无视所有标点、方括号和隐藏换行符陷阱的终极代码
    @PostMapping("/account-delete")
    @Transactional
    public Map<String, Object> deleteAccount(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "会话已过期，请重新登录");
        String rawAccount = payload.get("account");
        if (rawAccount == null || rawAccount.trim().isEmpty()) return Map.of("success", false, "error", "账号名不能为空");

        synchronized (accountYamlService.lock()) {
            try {
                File file = accountYamlService.getConfigFile();
                
                if (file != null && file.exists()) {
                    List<String> lines = Files.readAllLines(file.toPath());
                    List<String> newLines = new ArrayList<>();
                    boolean inTarget = false;
                    int targetIndent = -1;
                    boolean accountFoundAndDeleted = false;

                    for (String line : lines) {
                        // 使用全新的降维匹配逻辑，命中率100%
                        if (!inTarget && accountYamlService.isTargetYamlKey(line, rawAccount)) {
                            inTarget = true;
                            targetIndent = accountYamlService.getLeadingWhitespaceCount(line);
                            accountFoundAndDeleted = true;
                            continue; // 精准吞噬第一行键
                        }
                        
                        if (inTarget) {
                            if (line.trim().isEmpty()) continue; // 吞噬空行
                            
                            int currentIndent = accountYamlService.getLeadingWhitespaceCount(line);
                            
                            // 只要不比目标缩进更深且不是纯注释，说明目标的辖区结束了
                            if (currentIndent <= targetIndent && !line.trim().startsWith("#")) {
                                inTarget = false;
                                newLines.add(line); // 保留下一个兄弟节点或外层节点
                            }
                            continue; // 继续吞噬目标辖区内的属性
                        }
                        newLines.add(line);
                    }
                    
                    if (accountFoundAndDeleted) {
                        Files.write(file.toPath(), newLines);
                        System.out.println("✅ 终极胜利：已成功对 application.yml 中的 [" + rawAccount + "] 节点执行物理级抹除！");
                    } else {
                        System.err.println("⚠️ 警告: YAML 中未能精准定位到账号节点 [" + rawAccount + "]，可能是该节点之前已被损坏。即将进入内存和数据库肃清阶段...");
                    }
                } else {
                    return Map.of("success", false, "error", "找不到 application.yml 配置文件");
                }

                // ==========================
                // 以下开始全量、深度的关联内存和数据库抹杀！
                // ==========================
                
                // 彻底砸碎干扰符，生成纯净的通缉令指纹
                String cleanTarget = rawAccount.replaceAll("[\"'\\s\\[\\]\\r\\n]", "");
                
                // 1. 无死角内存肃清
                List<String> keysToRemove = new ArrayList<>();
                for (String k : ociProperties.getAccounts().keySet()) {
                    if (k.replaceAll("[\"'\\s\\[\\]\\r\\n]", "").equals(cleanTarget)) {
                        keysToRemove.add(k);
                    }
                }
                
                for (String key : keysToRemove) {
                    OciProperties.Account acc = ociProperties.getAccounts().remove(key);
                    if (acc != null && acc.getKeyFile() != null) {
                        File keyFile = new File(acc.getKeyFile());
                        if (keyFile.exists() && isSafeKeyFileToDelete(keyFile)) {
                            keyFile.delete();
                        }
                    }
                    ScheduledTasksService.TRAFFIC_CACHE.remove(key);
                    ScheduledTasksService.UPDATE_TIME_CACHE.remove(key);
                    if (trafficLimitRepository != null) trafficLimitRepository.deleteById(key);
                }
                ociCloudService.clearProviderCache();

                // 2. 强力停止并彻底抹除数据库中所有属于该账户的任务记录
                if (creationTaskService != null) {
                    List<CreationTaskRecord> allTasks = creationTaskService.getAllTasks();
                    for (CreationTaskRecord t : allTasks) {
                        if (t.getAccount() != null && t.getAccount().replaceAll("[\"'\\s\\[\\]\\r\\n]", "").equals(cleanTarget)) {
                            creationTaskService.stopTask(t.getId());
                        }
                    }
                }
                if (creationTaskRepository != null) {
                    List<com.oci.ocimanager.entity.CreationTaskRecord> accountTasks = creationTaskRepository.findAll().stream()
                            .filter(t -> t.getAccount() != null && t.getAccount().replaceAll("[\"'\\s\\[\\]\\r\\n]", "").equals(cleanTarget))
                            .collect(Collectors.toList());
                    creationTaskRepository.deleteAll(accountTasks);
                }

                // 3. 清理内存中属于该账户的实例缓存，并收集 OCID 以供解绑
                List<String> accountOcids = new ArrayList<>();
                Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
                if (instances != null) {
                    for (Map.Entry<String, Map<String, String>> entry : instances.entrySet()) {
                        String accVal = entry.getValue().get("account");
                        if (accVal != null && accVal.replaceAll("[\"'\\s\\[\\]\\r\\n]", "").equals(cleanTarget)) {
                            accountOcids.add(entry.getKey());
                        }
                    }
                    accountOcids.forEach(instances::remove);
                }

                // 4. 账号删除级联处理完成

                // 5. 闭环：重新同步内存镜像到 Redis
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
                return Map.of("success", false, "error", "配置彻底清理阶段发生异常: " + e.getMessage());
            }
        }
    }

    @PostMapping("/import-account")
    public Map<String, Object> importAccount(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "会话已过期，请重新登录");
        
        try {
            String rawAccountName = payload.get("accountName");
            String configText = payload.get("configText");
            String privateKeyText = payload.get("privateKeyText");

            if (rawAccountName == null || rawAccountName.trim().isEmpty()) return Map.of("success", false, "error", "账号别名不能为空");
            if (configText == null || configText.trim().isEmpty()) return Map.of("success", false, "error", "配置内容不能为空");
            if (privateKeyText == null || privateKeyText.trim().isEmpty()) return Map.of("success", false, "error", "私钥内容不能为空");

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

            String basePath = new File("/root/oci-manager").exists() ? "/root/oci-manager" : ".";
            String keysPath = basePath + "/keys";
            new File(keysPath).mkdirs();
            
            String englishFileName = "key_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            String keyFilePath = keysPath + "/" + englishFileName + ".pem";
            
            // 🔒 keys 目录收紧为 700，避免同机其它用户读取私钥。
            try {
                Files.setPosixFilePermissions(Paths.get(keysPath), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            } catch (Exception ignored) {}

            String cleanPrivateKey = privateKeyText.trim().replace("\r\n", "\n");
            java.nio.file.Path keyPath = Paths.get(keyFilePath);
            Files.write(keyPath, cleanPrivateKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // 🔒 OCI 私钥文件权限收紧为 600（默认 umask 常为 0644，会导致私钥全局可读）。
            try {
                Files.setPosixFilePermissions(keyPath, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {}

            OciProperties.Account newAcc = new OciProperties.Account();
            newAcc.setUser(user);
            newAcc.setFingerprint(fingerprint);
            newAcc.setTenancy(tenancy);
            newAcc.setRegion(region);
            newAcc.setKeyFile(keyFilePath);
            // 确保若存在同名账号先剔除再追加，保持 LinkedHashMap 尾部有序追加
            Map<String, OciProperties.Account> currentAccounts = new LinkedHashMap<>(ociProperties.getAccounts());
            for (String k : new ArrayList<>(currentAccounts.keySet())) {
                if (k.replaceAll("[\"'\\s\\[\\]\\r\\n]", "").equals(cleanAccountName)) {
                    currentAccounts.remove(k);
                }
            }
            currentAccounts.put(cleanAccountName, newAcc);
            ociProperties.setAccounts(currentAccounts);

            synchronized (accountYamlService.lock()) {
                File ymlFile = accountYamlService.getConfigFile();

                if (ymlFile != null && ymlFile.exists()) {
                    List<String> lines = new ArrayList<>(Files.readAllLines(ymlFile.toPath()));
                    
                    List<String> cleanedLines = new ArrayList<>();
                    boolean inTarget = false;
                    int targetIndent = -1;

                    for (String line : lines) {
                        if (!inTarget && accountYamlService.isTargetYamlKey(line, cleanAccountName)) {
                            inTarget = true;
                            targetIndent = accountYamlService.getLeadingWhitespaceCount(line);
                            continue;
                        }

                        if (inTarget) {
                            if (line.trim().isEmpty()) continue;

                            int currentIndent = accountYamlService.getLeadingWhitespaceCount(line);

                            if (currentIndent <= targetIndent && !line.trim().startsWith("#")) {
                                inTarget = false;
                                cleanedLines.add(line);
                            }
                            continue;
                        }
                        cleanedLines.add(line);
                    }
                    
                    lines = cleanedLines;

                    int accountsLine = -1;
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).matches("^\\s*accounts:.*")) {
                            accountsLine = i;
                            break;
                        }
                    }
                    
                    if (accountsLine != -1) {
                        int accountsIndent = accountYamlService.getLeadingWhitespaceCount(lines.get(accountsLine));
                        int insertIndex = accountsLine + 1;
                        
                        // 寻找 accounts 节点下最后一个子项的末尾行，追加在最后，保持添加时间正序一致
                        for (int i = accountsLine + 1; i < lines.size(); i++) {
                            String l = lines.get(i);
                            String trimmed = l.trim();
                            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                                continue;
                            }
                            int indent = accountYamlService.getLeadingWhitespaceCount(l);
                            if (indent > accountsIndent) {
                                insertIndex = i + 1;
                            } else {
                                break;
                            }
                        }

                        String newYamlNode = String.format("    \"[%s]\":\n      user: \"%s\"\n      fingerprint: \"%s\"\n      tenancy: \"%s\"\n      region: \"%s\"\n      key-file: \"%s\"",
                                cleanAccountName, user, fingerprint, tenancy, region, keyFilePath);

                        // 如果插入位置前面不是空行且不是 accounts: 声明行，增加空行分隔
                        if (insertIndex > accountsLine + 1 && insertIndex <= lines.size() && !lines.get(insertIndex - 1).trim().isEmpty()) {
                            newYamlNode = "\n" + newYamlNode;
                        }

                        lines.add(insertIndex, newYamlNode);
                        Files.write(ymlFile.toPath(), String.join("\n", lines).getBytes());
                        // 同步刷新内存账号拓扑顺序，确保新号稳固排列在最新位置
                        ociProperties.setAccounts(accountYamlService.reorderAccounts(ociProperties.getAccounts()));
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
                        ScheduledTasksService.TRAFFIC_CACHE.put(cleanAccountName, usage);
                        ScheduledTasksService.UPDATE_TIME_CACHE.put(cleanAccountName, java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " UTC");
                    }
                } catch (Exception e) {
                    System.err.println("导入新账号预热异常 [" + cleanAccountName + "]: " + e.getMessage());
                    ScheduledTasksService.TRAFFIC_CACHE.put(cleanAccountName, -1.0);
                    ScheduledTasksService.UPDATE_TIME_CACHE.put(cleanAccountName, "暂无数据(新号接口未初始化，稍后自动重试)");
                }
            });

            telegramBotService.sendText(Long.parseLong(adminId), "🌟 **[后台配置更新]**\n管理员刚刚通过网页控制台导入了新账号: `" + cleanAccountName + "` 并在后台完成预热！");

            return Map.of("success", true, "msg", "账号导入成功，已在后台自动建立预热缓存！");
        } catch (Exception e) {
            return Map.of("success", false, "error", "导入处理时发生异常: " + e.getMessage());
        }
    }

    @PostMapping("/account/regions")
    public Map<String, Object> getAccountRegions(@RequestBody Map<String, Object> payload) {
        if (!checkAuth((String) payload.get("token"))) return Map.of("success", false, "error", "鉴权失败");
        String account = (String) payload.get("account");
        if (account == null || account.trim().isEmpty()) {
            return Map.of("success", false, "error", "缺少账号参数");
        }
        return ociCloudService.getAccountRegionInfo(account);
    }

    @PostMapping("/account/subscribe-region")
    public Map<String, Object> subscribeRegion(@RequestBody Map<String, Object> payload) {
        if (!checkAuth((String) payload.get("token"))) return Map.of("success", false, "error", "鉴权失败");
        String account = (String) payload.get("account");
        String region = (String) payload.get("region");
        if (account == null || account.trim().isEmpty() || region == null || region.trim().isEmpty()) {
            return Map.of("success", false, "error", "缺少账号或目标区域参数");
        }
        String homeRegion = (String) payload.get("homeRegion");
        Map<String, Object> res = ociCloudService.subscribeRegion(account, region, homeRegion);
        if (Boolean.TRUE.equals(res.get("success"))) {
            try {
                telegramBotService.sendText(Long.parseLong(adminId), "🌍 **[OCI 区域扩充通知]**\n管理员为账号 `" + account + "` 成功提交了新区域订阅: `" + res.get("regionName") + " (" + res.get("regionKey") + ")`！");
            } catch (Exception ignored) {}
        }
        return res;
    }

    /**
     * 🔒 防御纵深校验：仅允许删除位于 keys 目录下的私钥文件 (.pem / .key)，防止因配置被污染误删系统关键文件
     */
    private boolean isSafeKeyFileToDelete(File keyFile) {
        if (keyFile == null || !keyFile.isFile()) return false;
        try {
            java.nio.file.Path canonicalTarget = keyFile.toPath().toRealPath();
            String fileName = canonicalTarget.getFileName().toString().toLowerCase();
            if (!fileName.endsWith(".pem") && !fileName.endsWith(".key")) {
                return false;
            }
            java.nio.file.Path parentDir = canonicalTarget.getParent();
            return parentDir != null && "keys".equals(parentDir.getFileName().toString());
        } catch (Exception e) {
            return false;
        }
    }
}

