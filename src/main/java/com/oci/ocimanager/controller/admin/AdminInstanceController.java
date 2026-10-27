package com.oci.ocimanager.controller.admin;

import com.oci.ocimanager.service.AdminAuthService;
import com.oci.ocimanager.service.OciCloudService;
import com.oci.ocimanager.service.ScheduledTasksService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 实例管理接口：启停/删除/重命名/重装（含后台监控与旧卷清理）/一键 500Mbps。
 * 原属 AdminController，URL 保持不变。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminInstanceController {

    @Autowired private OciCloudService ociCloudService;
    @Autowired private AdminAuthService adminAuthService;
    @Autowired private com.oci.ocimanager.service.NotificationService notificationService;

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(AdminInstanceController.class.getName());

    // 重装监控调度器（随实例域从 AdminController 迁入）
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
    }

    private boolean checkAuth(String token) {
        return adminAuthService.checkAuth(token);
    }

    @PostMapping("/instance-enable-500mbps")
    public Map<String, Object> enableAmd500Mbps(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "会话已过期，请重新登录");
        String ocid = payload.get("ocid");
        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        if (!instances.containsKey(ocid)) return Map.of("success", false, "error", "未找到该机器，可能已删除");
        try {
            String publicIp = ociCloudService.enableAmd500Mbps(ocid, instances.get(ocid).get("account"));
            CompletableFuture.runAsync(() -> {
                ociCloudService.updateInstancesCache(true);
                if (ScheduledTasksService.getInstance() != null) {
                    ScheduledTasksService.getInstance().saveSystemCache();
                }
            });
            return Map.of("success", true, "publicIp", publicIp);
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "开启 500Mbps 失败", e);
            return com.oci.ocimanager.controller.ApiErrors.fail("开启 500Mbps 失败", e);
        }
    }

    @PostMapping({"/instance-disable-500mbps", "/instance-delete-500mbps"})
    public Map<String, Object> disable500Mbps(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "会话已过期，请重新登录");
        String ocid = payload.get("ocid");
        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        if (!instances.containsKey(ocid)) return Map.of("success", false, "error", "未找到该机器，可能已删除");
        try {
            String publicIp = ociCloudService.disable500Mbps(ocid, instances.get(ocid).get("account"));
            CompletableFuture.runAsync(() -> {
                ociCloudService.updateInstancesCache(true);
                if (ScheduledTasksService.getInstance() != null) {
                    ScheduledTasksService.getInstance().saveSystemCache();
                }
            });
            return Map.of("success", true, "publicIp", publicIp);
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "删除 500Mbps 失败", e);
            return com.oci.ocimanager.controller.ApiErrors.fail("关闭 500Mbps 失败", e);
        }
    }

    @PostMapping("/instance-enable-ipv6")
    public Map<String, Object> enableIpv6(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "会话已过期，请重新登录");
        String ocid = payload.get("ocid");
        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        if (!instances.containsKey(ocid)) return Map.of("success", false, "error", "未找到该机器，可能已删除");
        try {
            String ipv6 = ociCloudService.enableIpv6(ocid, instances.get(ocid).get("account"));
            CompletableFuture.runAsync(() -> {
                ociCloudService.updateInstancesCache(true);
                if (ScheduledTasksService.getInstance() != null) {
                    ScheduledTasksService.getInstance().saveSystemCache();
                }
            });
            return Map.of("success", true, "ipv6", ipv6 != null ? ipv6 : "");
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "开启 IPv6 失败", e);
            return com.oci.ocimanager.controller.ApiErrors.fail("开启 IPv6 失败", e);
        }
    }

    @PostMapping({"/instance-disable-ipv6", "/instance-delete-ipv6"})
    public Map<String, Object> disableIpv6(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "会话已过期，请重新登录");
        String ocid = payload.get("ocid");
        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        if (!instances.containsKey(ocid)) return Map.of("success", false, "error", "未找到该机器，可能已删除");
        try {
            boolean ok = ociCloudService.disableIpv6(ocid, instances.get(ocid).get("account"));
            CompletableFuture.runAsync(() -> {
                ociCloudService.updateInstancesCache(true);
                if (ScheduledTasksService.getInstance() != null) {
                    ScheduledTasksService.getInstance().saveSystemCache();
                }
            });
            return Map.of("success", ok);
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "删除 IPv6 失败", e);
            return com.oci.ocimanager.controller.ApiErrors.fail("删除 IPv6 失败", e);
        }
    }

    @PostMapping("/instance-action")
    public Map<String, Object> instanceAction(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false);
        String ocid = payload.get("ocid");
        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        if (!instances.containsKey(ocid)) {
            instances = ociCloudService.updateInstancesCache();
        }
        if (!instances.containsKey(ocid)) return Map.of("success", false, "error", "未找到该机器，可能已删除");
        String action = payload.get("action");
        String account = instances.get(ocid).get("account");
        boolean ok = ociCloudService.instanceAction(ocid, action, account);
        if (ok) {
            String targetState = "START".equalsIgnoreCase(action) ? "STARTING" : "STOPPING";
            Map<String, String> instInfo = instances.get(ocid);
            if (instInfo != null) {
                instInfo.put("state", targetState);
            }
            trackInstanceStatusUntilStable(ocid, account);
        }
        return Map.of("success", ok, "error", ok ? "" : "甲骨文 API 拒绝操作");
    }

    private void trackInstanceStatusUntilStable(String ocid, String account) {
        trackInstanceStatusUntilStable(ocid, account, 0);
    }

    private void trackInstanceStatusUntilStable(String ocid, String account, int attempt) {
        if (attempt >= 30) {
            return;
        }
        scheduler.schedule(() -> {
            try {
                Map<String, String> info = ociCloudService.refreshSingleInstance(ocid, account);
                String state = info != null ? info.get("state") : null;
                if (state != null && ("RUNNING".equalsIgnoreCase(state) || "STOPPED".equalsIgnoreCase(state) || "TERMINATED".equalsIgnoreCase(state))) {
                    LOG.info("实例 [" + ocid + "] 状态已稳定为: " + state);
                    return;
                }
                trackInstanceStatusUntilStable(ocid, account, attempt + 1);
            } catch (Exception e) {
                LOG.log(java.util.logging.Level.FINE, "状态跟踪异常: " + e.getMessage());
            }
        }, 4, TimeUnit.SECONDS);
    }
    
    @PostMapping("/instance-delete")
    public Map<String, Object> deleteInstance(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "会话已过期，请重新登录");
        String ocid = payload.get("ocid");
        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        if (!instances.containsKey(ocid)) return Map.of("success", false, "error", "未找到该机器，可能已删除");
        
        boolean ok = ociCloudService.deleteInstance(ocid, instances.get(ocid).get("account"));
        if (ok) {
            instances.remove(ocid);
            if (ScheduledTasksService.getInstance() != null) {
                ScheduledTasksService.getInstance().saveSystemCache();
            }
        }
        return Map.of("success", ok, "error", ok ? "" : "甲骨文 API 拒绝操作，可能是权限不足或该机器有进行中的任务");
    }

    @PostMapping("/instance-rename")
    public Map<String, Object> renameInstance(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false, "error", "会话已过期，请重新登录");
        String ocid = payload.get("ocid");
        String newName = payload.get("newName");

        if (newName == null || newName.trim().isEmpty()) {
            return Map.of("success", false, "error", "新实例名称不能为空");
        }

        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        if (!instances.containsKey(ocid)) return Map.of("success", false, "error", "未找到该机器，可能已删除");

        String account = instances.get(ocid).get("account");
        boolean ok = ociCloudService.renameInstance(ocid, newName.trim(), account);

        if (ok) {
            CompletableFuture.runAsync(() -> { ociCloudService.updateInstancesCache(true); });
            return Map.of("success", true);
        } else {
            return Map.of("success", false, "error", "甲骨文 API 拒绝操作，可能是权限不足或实例状态异常");
        }
    }

    @PostMapping("/instance-reinstall")
    public Map<String, Object> reinstallInstance(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false);
        try {
            String ocid = payload.get("ocid");
            String customImageOcid = payload.get("imageOcid");
            String osName = payload.get("osName");
            String osVersion = payload.get("osVersion");
            String authType = payload.get("authType") != null ? payload.get("authType").trim() : "";
            String rawSshKey = payload.get("sshKey");
            String sshKey = rawSshKey != null ? rawSshKey.trim() : "";
            String rawRootPwd = payload.get("rootPwd");
            String rootPwd = rawRootPwd != null ? rawRootPwd.trim() : "";
            boolean rootLogin = payload.containsKey("rootLogin") ? "true".equalsIgnoreCase(String.valueOf(payload.get("rootLogin"))) : !rootPwd.isEmpty();

            if ("password".equalsIgnoreCase(authType)) {
                rootLogin = true;
                sshKey = "";
            } else if ("key".equalsIgnoreCase(authType)) {
                rootLogin = false;
                rootPwd = "";
            }
            
            String instanceName = payload.get("instanceName"); 
            
            boolean hasPwd = rootLogin && !rootPwd.isEmpty() && !"null".equalsIgnoreCase(rootPwd);
            boolean hasSsh = !sshKey.isEmpty() && !"null".equalsIgnoreCase(sshKey);
            
            if ("password".equalsIgnoreCase(authType) && !hasPwd) {
                return Map.of("success", false, "error", "请填写或生成 Root 密码！");
            }
            if ("key".equalsIgnoreCase(authType) && !hasSsh) {
                return Map.of("success", false, "error", "请填写或生成 SSH 公钥！");
            }
            if (!hasPwd && !hasSsh) {
                return Map.of("success", false, "error", "必须填写 Root 密码或 SSH 公钥，否则系统重装后将无法登录！");
            }

            String safeRootPwd = hasPwd ? rootPwd : null;
            String safeSsh = hasSsh ? sshKey : null;
            
            Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
            if (!instances.containsKey(ocid)) return Map.of("success", false, "error", "未找到该机器，可能已删除");
            
            String account = instances.get(ocid).get("account");
            String defaultInstanceName = instances.get(ocid).get("name");

            String oldBootVolumeId = null;
            try {
                List<Map<String, String>> bvs = ociCloudService.getBootVolumes(account);
                for (Map<String, String> bv : bvs) {
                    if (ocid.equals(bv.get("instanceId"))) {
                        oldBootVolumeId = bv.get("id");
                        break;
                    }
                }
            } catch (Exception ignored) {
            }

            // 🧹 重装前主动清理该账号下所有未挂载的孤立引导卷，释放 bootVolume 配额，
            //    避免 OCI 在创建新引导卷时因配额不足而拒绝（QuotaExceeded）。
            try {
                int cleaned = ociCloudService.cleanUnattachedBootVolumes(account);
                if (cleaned > 0) {
                    LOG.info("🧹 重装前自动清理了 " + cleaned + " 个未挂载的孤立引导卷，已释放配额空间。");
                    // 等待 OCI 后端配额计数刷新（删除操作异步生效）
                    Thread.sleep(5000);
                }
            } catch (Exception e) {
                LOG.warning("⚠️ 重装前清理引导卷时出现异常（不影响重装流程）: " + e.getMessage());
            }
            
            ociCloudService.reinstallInstance(ocid, account, instanceName, osName, osVersion, customImageOcid, safeSsh, safeRootPwd);
            
            startReinstallMonitor(ocid, account, defaultInstanceName, safeRootPwd, oldBootVolumeId);
            
            return Map.of("success", true, "generatedPwd", (hasPwd ? safeRootPwd : "（通过 SSH 密钥登录）"));
        } catch (Exception e) {
            return com.oci.ocimanager.controller.ApiErrors.fail("系统重装失败", e);
        }
    }

    private void startReinstallMonitor(String ocid, String account, String instanceName, String rootPwd, String oldBootVolumeId) {
        String safePwd = (rootPwd != null) ? rootPwd.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : "";
        String safeInstance = (instanceName != null) ? instanceName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : "";
        String safeAcc = (account != null) ? account.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : "";

        String pwdInfo = (rootPwd != null && !rootPwd.isEmpty()) ? "🔑 临时密码: <tg-spoiler><code>" + safePwd + "</code></tg-spoiler>" : "🔑 登录方式: SSH 密钥";
        notificationService.send("系统重装任务开始", "⏳ <b>系统重装任务开始</b>\n\n实例: <code>" + safeInstance + "</code>\n账号: <code>" + safeAcc + "</code>\n" + pwdInfo + "\n\n正在替换引导卷，将在后台自动跟进开机状态...", "INFO", "INSTANCE");
        scheduleMonitorTask(ocid, account, instanceName, rootPwd, oldBootVolumeId, 0);
    }

    private void scheduleMonitorTask(String ocid, String account, String instanceName, String rootPwd, String oldBootVolumeId, int attempt) {
        String safePwd = (rootPwd != null) ? rootPwd.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : "";
        String safeInstance = (instanceName != null) ? instanceName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") : "";

        if (attempt >= 40) { 
            notificationService.send("系统重装监控超时", "💿 ⚠️ <b>系统重装监控超时</b>\n\n实例: <code>" + safeInstance + "</code>\n状态: 超过20分钟未能启动，请前往 OCI 控制台检查机器是否卡住。", "WARNING", "INSTANCE");
            return;
        }

        scheduler.schedule(() -> {
            try {
                String state = ociCloudService.getInstanceStatus(ocid, account);
                if ("RUNNING".equalsIgnoreCase(state)) {
                    try { Thread.sleep(8000); } catch (InterruptedException ignored) {}
                    String newIp = ociCloudService.getInstanceIp(ocid, account, true);
                    if (newIp == null || newIp.contains("无") || newIp.contains("未")) {
                        newIp = "分配较慢，请稍后在面板查看";
                    }
                    String safeIp = newIp.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");

                    String successPwdInfo = (rootPwd != null && !rootPwd.isEmpty()) ? "🔑 Root 密码: <tg-spoiler><code>" + safePwd + "</code></tg-spoiler>" : "🔑 登录方式: SSH 密钥";
                    
                    String volumeCleanupInfo = "";
                    if (oldBootVolumeId != null && !oldBootVolumeId.isEmpty()) {
                        boolean deleted = ociCloudService.deleteBootVolume(oldBootVolumeId, account);
                        if (deleted) {
                            volumeCleanupInfo = "\n\n♻️ <b>存储空间自动释放</b>\n换下来的旧引导卷已自动为您成功销毁。";
                        } else {
                            volumeCleanupInfo = "\n\n⚠️ <b>存储空间释放失败</b>\n尝试销毁旧引导卷失败，这可能是由于甲骨文尚未完全解除其占用状态，请稍后在面板手动清理闲置引导卷。";
                        }
                    }

                    notificationService.send("系统重装成功", "💿 ✅ <b>系统重装成功</b>\n\n实例: <code>" + safeInstance + "</code>\n🌐 公网 IP: <code>" + safeIp + "</code>\n状态: 引导卷替换完成，<b>已成功开机运行！</b>\n" + successPwdInfo + "\n系统已更新。" + volumeCleanupInfo, "SUCCESS", "INSTANCE");
                } else {
                    if ("STOPPED".equalsIgnoreCase(state)) ociCloudService.instanceAction(ocid, "START", account);
                    scheduleMonitorTask(ocid, account, instanceName, rootPwd, oldBootVolumeId, attempt + 1);
                }
            } catch (Exception e) {
                LOG.warning("系统重装监控异常: " + e.getMessage());
                notificationService.send("系统重装监控异常", "💿 ❌ <b>系统重装监控异常</b>\n\n实例: <code>" + safeInstance + "</code>\n请到网页后台或服务日志查看详情。", "DANGER", "INSTANCE");
            }
        }, 30, TimeUnit.SECONDS);
    }
}
