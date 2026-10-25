package com.oci.ocimanager.service;

import com.oci.ocimanager.config.OciProperties;
import com.oci.ocimanager.entity.CreationTaskRecord;
import com.oci.ocimanager.repository.CreationTaskRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

@Service
public class CreationTaskService {

    @Autowired
    private CreationTaskRepository taskRepository;

    @Autowired
    private OciCloudService ociCloudService;

    @Autowired
    private com.oci.ocimanager.config.OciProperties ociProperties;

    @Autowired
    private TelegramBotService telegramBotService;

    private final Map<String, Thread> runningThreads = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> taskFlags = new ConcurrentHashMap<>();
    // 🐞 显式停止标记：worker 退出时据此兜底删除数据库记录，杜绝“停止后迟到 save() 复活任务”。
    private final Set<String> stoppedTasks = ConcurrentHashMap.newKeySet();

    // 🌟 核心内存优化：使用线程安全的 LRU (Least Recently Used) 缓存替换无限增长的 HashMap。
    // 最多只在内存中保留最近 20 个任务的日志，超出后自动丢弃最旧的日志，彻底切断内存泄漏源头！
    private final Map<String, StringBuffer> taskLogs = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, StringBuffer>(24, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, StringBuffer> eldest) {
                    return size() > 20; 
                }
            }
    );
    
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final Semaphore GLOBAL_INIT_LOCK = new Semaphore(2);

    private String getNowTime() {
        return timeFormatter.format(Instant.now());
    }

    public void appendLog(String taskId, String message) {
        taskLogs.computeIfAbsent(taskId, k -> new StringBuffer());
        StringBuffer log = taskLogs.get(taskId);
        
        // 🌟 核心内存优化：将单任务最大保留日志从 10000 字符降至 5000，节约 50% 的字符串内存开销
        if (log.length() > 5000) {
            log.delete(0, log.length() - 2500);
            log.insert(0, "...[由于内存限制，早期日志已被系统截断]...\n");
        }
        log.append(message);
    }

    private void saveTaskWithRetry(CreationTaskRecord task) {
        int retries = 5;
        while (retries > 0) {
            try {
                taskRepository.save(task);
                return;
            } catch (Exception e) {
                retries--;
                if (retries <= 0) {
                    System.err.println("❌ [CreationTaskService] 保存任务状态到数据库失败: " + e.getMessage());
                } else {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    public String getTaskLog(String taskId) {
        return taskLogs.containsKey(taskId) ? taskLogs.get(taskId).toString() : "该任务日志已被系统释放(节约内存)或任务不存在。";
    }

    @PostConstruct
    public void resumeTasksOnStartup() {
        System.out.println("🔄 正在从数据库恢复未完成的抢机任务...");
        try {
            List<CreationTaskRecord> runningTasks = taskRepository.findByStatus("RUNNING");
            if (runningTasks.isEmpty()) {
                System.out.println("✅ 没有需要恢复的抢机任务。");
                return;
            }
            
            for (CreationTaskRecord task : runningTasks) {
                System.out.println("▶️ 恢复任务: 账号 " + task.getAccount() + "，任务 ID: " + task.getId());
                appendLog(task.getId(), "🔄 [" + getNowTime() + "] 系统重启，已自动恢复抢占任务...\n");
                startTaskInternal(task);
                
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            }
            System.out.println("✅ 共恢复了 " + runningTasks.size() + " 个抢机任务。");
        } catch (Exception e) {
            System.err.println("❌ 恢复抢机任务失败: " + e.getMessage());
        }
    }

    public List<CreationTaskRecord> getAllTasks() {
        return taskRepository.findAll();
    }

    public String startTask(String account, String instanceName, String region, String shape, String osName, String osVersion, float cpu, float ram, long disk, int count, int interval, boolean ipv4, boolean ipv6, boolean rootLogin, String rootPwd, String sshKey) {
        CreationTaskRecord record = new CreationTaskRecord();
        String taskId = UUID.randomUUID().toString();
        record.setId(taskId);
        record.setAccount(account);
        record.setInstanceName(instanceName); 
        record.setRegion(region);
        record.setShape(shape);
        
        record.setOsName(osName);      
        record.setOsVersion(osVersion);
        record.setSshKey(sshKey);      
        
        record.setOcpus(cpu);
        record.setMemory(ram);
        record.setDisk(disk);
        record.setInstances(count > 0 ? count : 1);
        record.setSuccessCount(0);
        record.setRetryInterval(interval);
        record.setPublicIp(ipv4);
        record.setIpv6(ipv6);
        String trimmedPwd = (rootPwd != null) ? rootPwd.trim() : "";
        boolean hasTaskPwd = rootLogin && !trimmedPwd.isEmpty() && !"null".equalsIgnoreCase(trimmedPwd);
        record.setPassword(hasTaskPwd ? trimmedPwd : null);
        record.setAutoStop(hasTaskPwd);
        
        record.setStatus("RUNNING");
        record.setStartTime(System.currentTimeMillis());
        record.setAttemptCount(0); 
        
        saveTaskWithRetry(record);
        
        if (!taskFlags.containsKey(taskId) || !taskFlags.get(taskId).get()) {
            taskLogs.remove(taskId);
        }
        
        appendLog(taskId, "🚀 [" + getNowTime() + "] 任务已持久化并加入执行队列...\n");
        startTaskInternal(record);
        
        return "✅ 抢机任务已持久化并开始运行，服务器重启也不会丢失。";
    }

    private synchronized void startTaskInternal(CreationTaskRecord task) {
        String account = task.getAccount();
        String taskId = task.getId(); 

        if (taskFlags.containsKey(taskId) && taskFlags.get(taskId).get()) {
            System.out.println("⚠️ 拦截到重复触发的任务 [" + taskId + "]...");
            return;
        }

        AtomicBoolean isRunning = new AtomicBoolean(true);
        taskFlags.put(taskId, isRunning);

        // 🌟 核心防御更新：弃用 Thread.ofVirtual()，改为传统的 OS 平台线程 Thread.ofPlatform()！
        // 虚拟线程会被 OCI SDK 底层的 HTTP 阻塞操作“钉死”(Pinning) 从而耗尽所有的核心调度器，
        // 导致 Tomcat HTTP 线程无法获得 CPU 分配，进而引发全站 504 假死崩溃。
        // 改用传统 OS 线程后，抢机操作将被系统级隔离，再也不会波及前端响应！
        Thread taskThread = Thread.ofPlatform().name("creation-task-" + taskId.substring(0, 8)).start(() -> {
            boolean isFirstRun = true;
            int consecutiveAdLimitFailures = 0;
            try {
                while (isRunning.get()) {
                    boolean success = false;
                    int targetCount = task.getInstances() > 0 ? task.getInstances() : 1;

                    if (task.getSuccessCount() >= targetCount) {
                        task.setStatus("SUCCESS");
                        saveTaskWithRetry(task);
                        isRunning.set(false);
                        break;
                    }
                    
                    if (isFirstRun) {
                        try {
                            GLOBAL_INIT_LOCK.acquire();
                            appendLog(taskId, "⏳ [" + getNowTime() + "] 首次启动：正在排队校验底层网络与镜像依赖，避免连接拥堵...\n");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    String baseName = task.getInstanceName();
                    String currentInstanceName;
                    if (targetCount > 1 && baseName != null && !baseName.trim().isEmpty()) {
                        currentInstanceName = baseName.trim() + "-" + (task.getSuccessCount() + 1);
                    } else {
                        currentInstanceName = baseName;
                    }

                    try {
                        task.setAttemptCount(task.getAttemptCount() + 1);
                        saveTaskWithRetry(task);
                        
                        if (targetCount > 1) {
                            appendLog(taskId, "⏳ [" + getNowTime() + "] 正在尝试第 " + task.getAttemptCount() + " 次抢占 (目标进度: " + task.getSuccessCount() + "/" + targetCount + ")...\n");
                        } else {
                            appendLog(taskId, "⏳ [" + getNowTime() + "] 正在尝试第 " + task.getAttemptCount() + " 次抢占 (极速模式)...\n");
                        }
                        
                        try {
                            float safeCpu = (task.getOcpus() > 0) ? task.getOcpus() : 1.0f;
                            float safeRam = (task.getMemory() > 0) ? task.getMemory() : 1.0f;
                            // 🌟 修复：OCI 官方严格要求引导卷 (Boot Volume) 大小必须 >= 50GB
                            long safeDisk = (task.getDisk() >= 50L) ? task.getDisk() : 50L;
                            boolean safeIpv4 = task.isPublicIp();
                            boolean safeIpv6 = task.isIpv6();

                            String safeOsName = task.getOsName() != null ? task.getOsName() : task.getRegion();
                            String safeOsVersion = task.getOsVersion() != null ? task.getOsVersion() : task.getImageId();
                            
                            // 🌟 修复: 严格提取公钥。避免用户未输入时回退到纯数字的 Telegram ID 或字符串 "null"
                            String rawSsh = task.getSshKey();
                            String safeSshKey = (rawSsh != null && !rawSsh.trim().isEmpty() && !"null".equalsIgnoreCase(rawSsh.trim())) 
                                                ? rawSsh.trim() 
                                                : null;

                            boolean taskHasPwd = task.getPassword() != null && !task.getPassword().trim().isEmpty() && !"null".equalsIgnoreCase(task.getPassword().trim());
                            String effectivePwd = taskHasPwd ? task.getPassword().trim() : null;

                            String instanceId = ociCloudService.launchInstance(
                                    task.getAccount(),
                                    currentInstanceName,
                                    task.getRegion(),
                                    task.getShape(),    
                                    safeOsName,      
                                    safeOsVersion,   
                                    safeCpu,
                                    safeRam,
                                    safeDisk,
                                    safeIpv4,
                                    safeIpv6,
                                    effectivePwd,
                                    safeSshKey       
                            );
                            
                            if (instanceId != null && !instanceId.isEmpty()) {
                                success = true;
                                int newSuccessCount = task.getSuccessCount() + 1;
                                task.setSuccessCount(newSuccessCount);
                                if (newSuccessCount >= targetCount) {
                                    task.setStatus("SUCCESS");
                                }
                                saveTaskWithRetry(task);

                                String shortId = instanceId.length() > 20 ? instanceId.substring(0, 20) : instanceId;
                                appendLog(taskId, "🎉 [" + getNowTime() + "] 第 " + newSuccessCount + " / " + targetCount + " 台实例创建成功！(OCID: " + shortId + "...)\n");
                                
                                String ipAddress = "获取中(可稍后在后台刷新)";
                                
                                if (task.isPublicIp()) {
                                    appendLog(taskId, "⏳ [" + getNowTime() + "] 正在等待 OCI 分配公网 IP，请稍候...\n");
                                    for (int i = 0; i < 10; i++) { 
                                        try {
                                            Thread.sleep(3000); 
                                            String fetchedIp = ociCloudService.getInstanceIp(instanceId, task.getAccount(), true);
                                            if (fetchedIp != null && !fetchedIp.isEmpty() && !fetchedIp.contains("无") && !fetchedIp.contains("未")) {
                                                ipAddress = fetchedIp;
                                                appendLog(taskId, "✅ [" + getNowTime() + "] 成功获取到公网 IP: " + ipAddress + "\n");
                                                break;
                                            }
                                        } catch (Exception ignored) {}
                                    }
                                    if ("获取中(可稍后在后台刷新)".equals(ipAddress)) {
                                        appendLog(taskId, "⏳ [" + getNowTime() + "] 公网 IP 正在后台异步绑定，可稍后在实例列表中刷新查看...\n");
                                    }
                                } else {
                                    ipAddress = "未分配公网 IP (用户未勾选)";
                                }

                                String shapeDescription = task.getShape();
                                if (shapeDescription != null && shapeDescription.contains("Flex")) {
                                    String c = (safeCpu == (long)safeCpu) ? String.valueOf((long)safeCpu) : String.valueOf(safeCpu);
                                    String r = (safeRam == (long)safeRam) ? String.valueOf((long)safeRam) : String.valueOf(safeRam);
                                    if(!c.contains(".")) c += ".0";
                                    if(!r.contains(".")) r += ".0";
                                    shapeDescription += String.format(" (%s核 / %sGB)", c, r);
                                } else {
                                    shapeDescription += " (AMD Micro)";
                                }

                                // 🌟 修复：接入原生 HTML <tg-spoiler> 标签与 HTML 实体转义
                                String safePwd = (effectivePwd != null) ? effectivePwd.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : "";
                                String safeAcc = (task.getAccount() != null) ? task.getAccount().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : "";
                                String safeIp = (ipAddress != null) ? ipAddress.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : "";
                                String safeShape = (shapeDescription != null) ? shapeDescription.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : "";
                                String safeOs = (safeOsName + " " + safeOsVersion).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");

                                String pwdInfo;
                                if (taskHasPwd && safeSshKey != null) {
                                    pwdInfo = "🔑 登录方式: 密码 + SSH 密钥双重登录\n" +
                                              "🔑 Root 密码: <tg-spoiler><code>" + safePwd + "</code></tg-spoiler>";
                                } else if (taskHasPwd) {
                                    pwdInfo = "🔑 Root 密码: <tg-spoiler><code>" + safePwd + "</code></tg-spoiler>";
                                } else {
                                    pwdInfo = "🔑 登录方式: SSH 密钥安全登录 (已注入)";
                                }

                                String tgMsg = "🎉 <b>OCI 抢机成功通知 (" + newSuccessCount + "/" + targetCount + ")</b> 🎉\n\n" +
                                        "🟢 账号名称: <code>" + safeAcc + "</code>\n" +
                                        "📦 实例架构: " + safeShape + "\n" +
                                        "💿 操作系统: " + safeOs + "\n" +
                                        "💾 启动磁盘: " + safeDisk + " GB\n" +
                                        "🌐 公网 IP: <code>" + safeIp + "</code>\n" +
                                        pwdInfo + "\n\n" +
                                        (newSuccessCount >= targetCount ? "机器已全部开出，请及时前往后台确认并做好数据备份！" : "已成功抢到 1 台，系统将自动继续抢占剩余 " + (targetCount - newSuccessCount) + " 台！");

                                try {
                                    // 🌟 使用 HTML 模式发送
                                    telegramBotService.sendHtmlMessage(tgMsg);
                                    appendLog(taskId, "📨 [" + getNowTime() + "] Telegram 推送消息已发送！\n");
                                } catch (Exception e) {
                                    appendLog(taskId, "⚠️ [" + getNowTime() + "] Telegram 推送失败: " + e.getMessage() + "\n");
                                }
                            }
                            
                        } catch (com.oracle.bmc.model.BmcException e) {
                            // 🌟 核心日志精简：截断原生长篇幅报错，抛弃冗余的 Tips 和 RequestID 等无用字符
                            String shortMsg = e.getMessage() != null ? e.getMessage().split("\n")[0] : "未知错误";
                            if (shortMsg.contains("(opc-request-id")) {
                                shortMsg = shortMsg.substring(0, shortMsg.indexOf("(opc-request-id")).trim();
                            }
                            // 进一步提取最核心的报错说明，去掉前面的 API 状态码和方法名描述，保留真正的错误内容
                            if (shortMsg.contains(")")) {
                                int lastBracket = shortMsg.lastIndexOf(")");
                                if (lastBracket < shortMsg.length() - 1) {
                                    String pureMsg = shortMsg.substring(lastBracket + 1).trim();
                                    if (!pureMsg.isEmpty()) shortMsg = pureMsg;
                                }
                            }

                            if (e.getStatusCode() == 500 && (e.getMessage().contains("capacity") || e.getMessage().contains("Out of host capacity") || e.getMessage().contains("Out of capacity"))) {
                                appendLog(taskId, "⚠️ [" + getNowTime() + "] 官方可用区资源不足，持续监控抢占中... | 官方返回: " + shortMsg + "\n");
                            } else if (e.getStatusCode() == 429) {
                                var fp429 = ociCloudService.getClientFingerprint(account);
                                int backoffMs = (fp429 != null) ? fp429.nextJitteredSleepMs(62000) : (58000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(12000));
                                appendLog(taskId, "⏳ [" + getNowTime() + "] 触发限流 (429)，正在执行差异化退避 " + (backoffMs / 1000) + " 秒以恢复配额...\n");
                                try {
                                    Thread.sleep(backoffMs);
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            } else if (e.getStatusCode() == 400 && (e.getMessage().contains("LimitExceeded") || "LimitExceeded".equals(e.getServiceCode()))) {
                                
                                appendLog(taskId, "⚠️ [" + getNowTime() + "] 触发配额耗尽警告。正在校验是否因网络波动导致了“幻影成功”...\n");
                                
                                boolean phantomFound = false;
                                try {
                                    Map<String, Map<String, String>> instances = ociCloudService.updateInstancesCache(false);
                                    for (Map.Entry<String, Map<String, String>> entry : instances.entrySet()) {
                                        Map<String, String> inst = entry.getValue();
                                        String cachedAcc = inst.get("account");
                                        String cachedName = inst.get("name").replace("[" + cachedAcc + "] ", "");
                                        
                                        if (account.equals(cachedAcc) && (currentInstanceName != null ? cachedName.equals(currentInstanceName) : (task.getInstanceName() != null && cachedName.equals(task.getInstanceName())))) {
                                            phantomFound = true;
                                            String foundId = entry.getKey();
                                            // 🐞 【死循环修复】幻影成功也必须计数并落库，否则 successCount 永远为 0，
                                            // targetCount 检查永不满足 → 每轮都重新“发现”同一台机器并反复推送，任务永不结束。
                                            // 计数推进后，currentInstanceName（依赖 successCount）也会随之改变，避免重复命中同一台。
                                            int newSuccessCount = task.getSuccessCount() + 1;
                                            task.setSuccessCount(newSuccessCount);
                                            try { taskRepository.save(task); } catch (Exception ignored) {}
                                            appendLog(taskId, "🎉 [" + getNowTime() + "] 喜报！核实发现机器在刚才其实已成功开出，只是未收到响应！(第 " + newSuccessCount + "/" + targetCount + " 台, OCID: " + foundId.substring(0, 20) + "...)\n");
                                            
                                            // 🌟 修复超时核实时的推送：同样使用 HTML 防御模式
                                            String safeAcc = account.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                                            String safeShape = task.getShape().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");

                                            String tgMsg = "🎉 <b>OCI 抢机成功通知 (核实补发)</b> 🎉\n\n" +
                                                    "🟢 账号名称: <code>" + safeAcc + "</code>\n" +
                                                    "📦 实例架构: " + safeShape + "\n" +
                                                    "系统已重新查岗核实，机器已确切存在！请前往后台查看。";
                                            try { telegramBotService.sendHtmlMessage(tgMsg); } catch (Exception ignored) {}
                                            
                                            success = true;
                                            break;
                                        }
                                    }
                                } catch (Exception ex) {
                                    // 忽略核实过程中的附带错误
                                }

                                if (!phantomFound) {
                                    OciProperties.Account accConfig = ociProperties.getAccounts().get(account);
                                    String tenancy = accConfig != null ? accConfig.getTenancy() : null;
                                    String targetRegion = (task.getRegion() != null && !task.getRegion().trim().isEmpty()) 
                                            ? task.getRegion() 
                                            : (accConfig != null ? accConfig.getRegion() : null);
                                    List<String> adNames = (tenancy != null && targetRegion != null) 
                                            ? OciCloudService.AD_LIST_CACHE.get(tenancy + "|" + targetRegion) 
                                            : null;
                                    int adCount = (adNames != null && !adNames.isEmpty()) ? adNames.size() : 1;

                                    String rawMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                                    boolean isStorageLimit = rawMsg.contains("volume-storage-size") || rawMsg.contains("total-storage-size") || rawMsg.contains("custom-boot-volume-size") || rawMsg.contains("storage");
                                    boolean isPublicIpLimit = rawMsg.contains("ephemeral-public-ip") || rawMsg.contains("public-ip");
                                    boolean isAdSpecific = !isStorageLimit && !isPublicIpLimit && (rawMsg.contains("availability domain") || rawMsg.contains("standard-e2-1-micro") || rawMsg.contains("micro") || rawMsg.contains("standard-a1") || rawMsg.contains("ocpu") || rawMsg.contains("core"));

                                    consecutiveAdLimitFailures++;

                                    // 🌟 多可用区智能轮转：如果该区域有多个可用区（如凤凰城有3个AD），且是可用区级配额限制，
                                    // 绝不轻易中断任务！自动切换尝试下一个可用区（因为免费号 AMD 配额往往只分配在其中某一个 AD 中）。
                                    if (isAdSpecific && adCount > 1 && consecutiveAdLimitFailures < adCount) {
                                        appendLog(taskId, "⚠️ [" + getNowTime() + "] 当前可用区该规格配额不足 (" + shortMsg + ")。\n" +
                                                "💡 检测到当前区域包含 " + adCount + " 个可用区，正在自动轮换至下一个可用区重试 (" + consecutiveAdLimitFailures + "/" + adCount + ")...\n");
                                        try {
                                            Thread.sleep(2000);
                                        } catch (InterruptedException ie) {
                                            Thread.currentThread().interrupt();
                                            break;
                                        }
                                        continue;
                                    }

                                    long reqDisk = (task.getDisk() >= 50L) ? task.getDisk() : 50L;
                                    String diag;
                                    String advice;

                                    if (isStorageLimit) {
                                        diag = "账号块存储/引导卷总容量已达上限 (免费号全号总上限为 200 GB)。当前已有机器或未彻底删除的孤儿引导卷已占满容量，无法再创建 " + reqDisk + " GB 磁盘。";
                                        advice = "请前往后台【引导卷管理】检查并彻底删除闲置引导卷；若已有机器总磁盘已达 200 GB，需先释放存储空间。";
                                    } else if (isPublicIpLimit) {
                                        diag = "公网 IPv4 地址配额已耗尽 (免费号最多允许 2 个临时公网 IP)。已有运行机器已占用全部公网 IP 额度。";
                                        advice = "请在新建抢机任务时【取消勾选分配公网 IP】(后续可通过 IPv6 访问)，或先释放已有闲置机器的公网 IP 后重试。";
                                    } else if (rawMsg.contains("e2.1.micro") || rawMsg.contains("standard-e2-1-micro") || rawMsg.contains("micro")) {
                                        diag = "AMD Micro 实例配额已耗尽 (免费号最多允许 2 台 AMD 机器)。" + (adCount > 1 ? "该区域所有 " + adCount + " 个可用区均已无可用配额。" : "");
                                        advice = "免费号最多只能运行 2 台 AMD Micro 机器。请确认是否已有正在运行或终止中的 AMD 实例。";
                                    } else if (rawMsg.contains("a1") || rawMsg.contains("core-count") || rawMsg.contains("ocpu") || rawMsg.contains("memory")) {
                                        diag = "ARM A1 核心或内存配额已达硬性上限 (免费号最多允许 4 核 / 24 GB 内存)。";
                                        advice = "请先删除该账号下的闲置机器释放额度后再试。";
                                    } else {
                                        diag = "账号配额已达上限 (" + shortMsg + ")";
                                        advice = "请检查账号资源占用情况或删除闲置资源释放额度。";
                                    }

                                    if (task.getSuccessCount() > 0) {
                                        appendLog(taskId, "🛑 [" + getNowTime() + "] 经核实，确实是账号配额已耗尽！\n" +
                                                "💡 诊断: " + diag + "\n" +
                                                "📋 官方详情: " + shortMsg + "\n" +
                                                "终止后续抢占任务 (之前已成功创建 " + task.getSuccessCount() + " / " + targetCount + " 台)。\n");
                                    } else {
                                        appendLog(taskId, "🛑 [" + getNowTime() + "] 经核实，确实是账号配额已耗尽！\n" +
                                                "💡 诊断: " + diag + "\n" +
                                                "📋 官方详情: " + shortMsg + "\n" +
                                                "⚠️ 为防止死循环导致该账号被风控，系统已主动终止此任务。\n" +
                                                "👉 建议: " + advice + "\n");
                                    }
                                    task.setStatus("STOPPED");
                                    try {
                                        taskRepository.save(task);
                                    } catch (Exception ignored) {}
                                    isRunning.set(false);
                                    break;
                                }

                            } else if (e.getStatusCode() == 401 || (e.getMessage() != null && e.getMessage().contains("The required information to complete authentication"))) {
                                appendLog(taskId, "⚠️ [" + getNowTime() + "] 账号鉴权未通过 (401)。若为刚导入的新账号，OCI 验证配置全球同步通常需 1~3 分钟，系统会自动重试，请耐心等待...\n");
                            } else if (e.getStatusCode() == 404) {
                                // 🌟 精准诊断 404：AMD Micro 仅限主区域、或架构在当前区域不可用
                                String shapeInfo = task.getShape() != null ? task.getShape() : "未知";
                                boolean isAmdMicro = shapeInfo.contains("E2.1.Micro") || shapeInfo.contains("E2.1");
                                if (isAmdMicro) {
                                    appendLog(taskId, "⚠️ [" + getNowTime() + "] 资源未找到 (404): " + shortMsg + "\n" +
                                            "💡 可能原因: AMD Micro 实例在非主区域(Home Region)不可用，或当前可用区暂无库存/镜像配置有误。系统将忽略此错误并继续重试...\n");
                                } else {
                                    appendLog(taskId, "⚠️ [" + getNowTime() + "] 资源未找到 (404): " + shortMsg + "\n" +
                                            "💡 可能原因: 所选架构 [" + shapeInfo + "] 在当前区域/可用区不可用，或镜像已过期。系统将继续重试...\n");
                                }
                            } else if (e.getStatusCode() == 409) {
                                appendLog(taskId, "⚠️ [" + getNowTime() + "] 资源冲突 (409): " + shortMsg + "，系统将自动重试...\n");
                            } else if (e.getStatusCode() == -1) {
                                String detail = (e.getCause() != null && e.getCause().getMessage() != null)
                                        ? e.getCause().getMessage().split("\n")[0]
                                        : shortMsg;
                                appendLog(taskId, "❌ [" + getNowTime() + "] 客户端底层异常 (-1): " + detail + " (非官方API拒绝，属底层传输/解析错误)\n");
                            } else {
                                appendLog(taskId, "❌ [" + getNowTime() + "] API拦截 (" + e.getStatusCode() + "): " + shortMsg + "\n");
                            }
                        } catch (Exception e) {
                            String shortMsg = e.getMessage() != null ? e.getMessage().split("\n")[0] : "未知异常";
                            appendLog(taskId, "❌ [" + getNowTime() + "] 遇到网络异常或系统阻力: " + shortMsg + "\n");
                        }

                    } finally {
                        if (isFirstRun) {
                            GLOBAL_INIT_LOCK.release();
                            isFirstRun = false;
                            appendLog(taskId, "✅ [" + getNowTime() + "] 环境缓存就绪！后续轮询请求将切入无阻塞极速模式！\n");
                        }
                    }

                    if (success) {
                        consecutiveAdLimitFailures = 0;
                        CompletableFuture.runAsync(() -> {
                            try {
                                ociCloudService.updateInstancesCache(true); 
                                if (ScheduledTasksService.getInstance() != null) {
                                    ScheduledTasksService.getInstance().saveSystemCache();
                                }
                            } catch (Exception e) {
                                System.err.println("抢机成功后自动更新缓存失败: " + e.getMessage());
                            }
                        });

                        if (task.getSuccessCount() >= targetCount) {
                            task.setStatus("SUCCESS");
                            saveTaskWithRetry(task);
                            appendLog(taskId, "🏁 [" + getNowTime() + "] 目标创建数量 (" + targetCount + " 台) 已全部成功抢占，任务完成！\n");
                            isRunning.set(false);
                            break; 
                        } else {
                            saveTaskWithRetry(task);
                            appendLog(taskId, "⏳ [" + getNowTime() + "] 已成功抢到 " + task.getSuccessCount() + " / " + targetCount + " 台，正继续尝试抢占下一台...\n");
                        }
                    }

                    int baseSleepTime = task.getRetryInterval() > 0 ? task.getRetryInterval() * 1000 : 30000;
                    var taskFp = ociCloudService.getClientFingerprint(account);
                    int sleepTime = (taskFp != null) ? taskFp.nextJitteredSleepMs(baseSleepTime) : baseSleepTime;
                    try {
                        Thread.sleep(sleepTime); 
                    } catch (InterruptedException e) {
                        appendLog(taskId, "⏹️ [" + getNowTime() + "] 抢占线程被主动中止。\n");
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                taskFlags.remove(taskId);
                runningThreads.remove(taskId);
                // 🐞 兜底：若本任务是被显式停止的，线程退出时再删一次数据库记录，
                // 抵消停止前后线程内任何迟到的 save() 复活效果。此处在整个 worker 生命周期最后执行，
                // 之后不再有任何 save()，故能彻底关闭“复活”窗口。仅停止时删除，正常 SUCCESS 记录保留。
                if (stoppedTasks.remove(taskId)) {
                    try {
                        taskRepository.deleteById(taskId);
                    } catch (Exception ignored) {}
                }
            }
        });

        runningThreads.put(taskId, taskThread);
    }

    public String stopTask(String taskId) {
        boolean wasRunning = taskFlags.containsKey(taskId);

        // 🐞 【任务复活修复】先给 worker 发停止信号（置位 flag + 中断），再删库；
        // 并打上显式停止标记，让 worker 在 finally 里兜底再删一次，杜绝停止后迟到 save() 复活任务。
        if (wasRunning) {
            stoppedTasks.add(taskId);
            AtomicBoolean flag = taskFlags.get(taskId);
            if (flag != null) flag.set(false);

            Thread thread = runningThreads.get(taskId);
            if (thread != null) {
                thread.interrupt();
            }
            // 手动停止任务时直接从内存清理日志
            taskLogs.remove(taskId);
        }

        try {
            Optional<CreationTaskRecord> taskOpt = taskRepository.findById(taskId);
            if (taskOpt.isPresent()) {
                taskRepository.delete(taskOpt.get());
            }
        } catch (Exception e) {
            System.err.println("查找并删除任务失败: " + e.getMessage());
        }

        if (wasRunning) {
            return "✅ 已发送停止指令，任务已终止并从控制台移除。";
        }
        // 没有正在运行的 worker 兜底，直接清理标记避免泄漏
        stoppedTasks.remove(taskId);
        return "✅ 已从数据库中清理该任务记录。";
    }
}
