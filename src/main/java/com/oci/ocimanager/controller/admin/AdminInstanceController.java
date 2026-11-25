package com.oci.ocimanager.controller.admin;

import com.oci.ocimanager.service.AdminAuthService;
import com.oci.ocimanager.service.OciCloudService;
import com.oci.ocimanager.service.ScheduledTasksService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    @Autowired private com.oci.ocimanager.service.TelegramBotService telegramBotService;

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(AdminInstanceController.class.getName());

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Set<String> changingIpOcids = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // 合法电源动作白名单，阻断非预期或恶意参数注入
    private static final Set<String> ALLOWED_POWER_ACTIONS = Set.of(
            "START", "STOP", "SOFTSTOP", "RESET", "SOFTRESET"
    );

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
    }

    private boolean checkAuth(HttpServletRequest request, Map<String, ?> payload) {
        return adminAuthService.checkAuth(request, payload);
    }

    @PostMapping("/instance-enable-500mbps")
    public Map<String, Object> enableAmd500Mbps(HttpServletRequest request, @RequestBody(required = false) Map<String, String> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        String ocid = payload.get("ocid");
        if (ocid == null || ocid.isBlank()) return Map.of("success", false, "error", "缺少实例 OCID");
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
    public Map<String, Object> disable500Mbps(HttpServletRequest request, @RequestBody(required = false) Map<String, String> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        String ocid = payload.get("ocid");
        if (ocid == null || ocid.isBlank()) return Map.of("success", false, "error", "缺少实例 OCID");
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
    public Map<String, Object> enableIpv6(HttpServletRequest request, @RequestBody(required = false) Map<String, String> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        String ocid = payload.get("ocid");
        if (ocid == null || ocid.isBlank()) return Map.of("success", false, "error", "缺少实例 OCID");
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
    public Map<String, Object> disableIpv6(HttpServletRequest request, @RequestBody(required = false) Map<String, String> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        String ocid = payload.get("ocid");
        if (ocid == null || ocid.isBlank()) return Map.of("success", false, "error", "缺少实例 OCID");
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
    public Map<String, Object> instanceAction(HttpServletRequest request, @RequestBody(required = false) Map<String, String> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        String action = payload.get("action");
        if (action == null || !ALLOWED_POWER_ACTIONS.contains(action.trim().toUpperCase(Locale.ROOT))) {
            return Map.of("success", false, "error", "非法的实例电源操作指令");
        }
        String cleanAction = action.trim().toUpperCase(Locale.ROOT);
        String ocid = payload.get("ocid");
        if (ocid == null || ocid.isBlank()) return Map.of("success", false, "error", "缺少实例 OCID");
        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        if (!instances.containsKey(ocid)) {
            instances = ociCloudService.updateInstancesCache();
        }
        if (!instances.containsKey(ocid)) return Map.of("success", false, "error", "未找到该机器，可能已删除");
        String account = instances.get(ocid).get("account");
        boolean ok = ociCloudService.instanceAction(ocid, cleanAction, account);
        if (ok) {
            if ("START".equals(cleanAction) || "STOP".equals(cleanAction) || "SOFTSTOP".equals(cleanAction)) {
                ScheduledTasksService.recordManualInstancePowerAction(ocid, cleanAction);
            }
            String targetState = "START".equalsIgnoreCase(cleanAction) ? "STARTING" : "STOPPING";
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
    public Map<String, Object> deleteInstance(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        Object ocidObj = payload.get("ocid");
        String ocid = ocidObj != null ? String.valueOf(ocidObj) : null;
        if (ocid == null || ocid.isBlank()) return Map.of("success", false, "error", "缺少实例 OCID");

        Map<String, Object> stepUp = adminAuthService.verifyOrRequireStepUp(request, payload);
        if (!Boolean.TRUE.equals(stepUp.get("success"))) {
            return stepUp;
        }

        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        if (!instances.containsKey(ocid)) return Map.of("success", false, "error", "未找到该机器，可能已删除");
        
        boolean ok = ociCloudService.deleteInstance(ocid, instances.get(ocid).get("account"));
        if (ok) {
            instances.remove(ocid);
            ScheduledTasksService.recordManualInstancePowerAction(ocid, "DELETE");
            if (ScheduledTasksService.getInstance() != null) {
                ScheduledTasksService.getInstance().saveSystemCache();
            }
        }
        return Map.of("success", ok, "error", ok ? "" : "甲骨文 API 拒绝操作，可能是权限不足或该机器有进行中的任务");
    }

    @PostMapping("/instance-change-ip")
    public Map<String, Object> changeInstanceIp(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        Object ocidObj = payload.get("ocid");
        String ocid = ocidObj != null ? String.valueOf(ocidObj).trim() : "";
        if (ocid.isEmpty()) return Map.of("success", false, "error", "缺少实例 OCID");

        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        if (!instances.containsKey(ocid)) {
            instances = ociCloudService.updateInstancesCache();
        }
        if (!instances.containsKey(ocid)) {
            return Map.of("success", false, "error", "未找到该实例，请先同步实例状态");
        }

        if (!changingIpOcids.add(ocid)) {
            return Map.of("success", false, "error", "该实例正在更换公网 IP 中，请勿重复点击");
        }

        try {
            Map<String, String> info = instances.get(ocid);
            String account = info.get("account");
            String instName = info.getOrDefault("name", ocid);
            String newIp = ociCloudService.changeInstanceIp(ocid, account);
            if (newIp != null && !newIp.isBlank()) {
                if (telegramBotService != null) {
                    telegramBotService.getAndIncrementTotalSystemChanges();
                }
                CompletableFuture.runAsync(() -> {
                    try {
                        ociCloudService.refreshSingleInstance(ocid, account);
                    } catch (Exception ignored) {}
                });
                if (notificationService != null) {
                    notificationService.send("更换公网 IP 成功",
                            "🌐 <b>公网 IP 更换成功</b>\n实例: <code>" + instName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</code>\n新公网 IP: <code>" + newIp + "</code>",
                            "SUCCESS", "INSTANCE");
                }
                return Map.of("success", true, "publicIp", newIp, "message", "公网 IP 已成功更换为: " + newIp);
            }
            return Map.of("success", false, "error", "未能分配到新的公网 IP（请确认实例处于运行状态、网卡具备私网 IP 且账号临时公网 IP 配额未耗尽）");
        } catch (IllegalStateException ise) {
            return Map.of("success", false, "error", ise.getMessage());
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "更换公网 IP 失败", e);
            return com.oci.ocimanager.controller.ApiErrors.fail("更换公网 IP 失败", e);
        } finally {
            changingIpOcids.remove(ocid);
        }
    }

    @PostMapping("/instance-rename")
    public Map<String, Object> renameInstance(HttpServletRequest request, @RequestBody(required = false) Map<String, String> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        String ocid = payload.get("ocid");
        if (ocid == null || ocid.isBlank()) return Map.of("success", false, "error", "缺少实例 OCID");
        String newName = payload.get("newName");

        if (newName == null || newName.trim().isEmpty()) {
            return Map.of("success", false, "error", "新实例名称不能为空");
        }
        String cleanNewName = newName.trim();
        if (cleanNewName.length() > 255) {
            return Map.of("success", false, "error", "实例名称长度不能超过 255 个字符");
        }
        for (int i = 0; i < cleanNewName.length(); i++) {
            char c = cleanNewName.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return Map.of("success", false, "error", "实例名称包含非法控制字符");
            }
        }

        Map<String, Map<String, String>> instances = ociCloudService.syncAllInstances();
        if (!instances.containsKey(ocid)) return Map.of("success", false, "error", "未找到该机器，可能已删除");

        String account = instances.get(ocid).get("account");
        boolean ok = ociCloudService.renameInstance(ocid, cleanNewName, account);

        if (ok) {
            CompletableFuture.runAsync(() -> { ociCloudService.updateInstancesCache(true); });
            return Map.of("success", true);
        } else {
            return Map.of("success", false, "error", "甲骨文 API 拒绝操作，可能是权限不足或实例状态异常");
        }
    }

    @PostMapping("/instance-reinstall")
    public Map<String, Object> reinstallInstance(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> payload) {
        if (!checkAuth(request, payload)) return Map.of("success", false, "error", "会话已过期，请重新登录");
        if (payload == null) return Map.of("success", false, "error", "缺少请求参数");
        try {
            String ocid = payload.get("ocid") != null ? String.valueOf(payload.get("ocid")).trim() : "";
            if (ocid.isEmpty()) return Map.of("success", false, "error", "缺少实例 OCID");

            Map<String, Object> stepUp = adminAuthService.verifyOrRequireStepUp(request, payload);
            if (!Boolean.TRUE.equals(stepUp.get("success"))) {
                return stepUp;
            }

            String customImageOcid = payload.get("imageOcid") != null ? String.valueOf(payload.get("imageOcid")) : null;
            String osName = payload.get("osName") != null ? String.valueOf(payload.get("osName")) : null;
            String osVersion = payload.get("osVersion") != null ? String.valueOf(payload.get("osVersion")) : null;
            String authType = payload.get("authType") != null ? String.valueOf(payload.get("authType")).trim() : "";
            String rawSshKey = payload.get("sshKey") != null ? String.valueOf(payload.get("sshKey")) : null;
            String sshKey = rawSshKey != null ? rawSshKey.trim() : "";
            String rawRootPwd = payload.get("rootPwd") != null ? String.valueOf(payload.get("rootPwd")) : null;
            String rootPwd = rawRootPwd != null ? rawRootPwd.trim() : "";
            boolean rootLogin = payload.containsKey("rootLogin") ? "true".equalsIgnoreCase(String.valueOf(payload.get("rootLogin"))) : !rootPwd.isEmpty();

            if ("password".equalsIgnoreCase(authType)) {
                rootLogin = true;
                sshKey = "";
            } else if ("key".equalsIgnoreCase(authType)) {
                rootLogin = false;
                rootPwd = "";
            }
            
            String instanceName = payload.get("instanceName") != null ? String.valueOf(payload.get("instanceName")) : null;
            
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
            if (hasSsh && !com.oci.ocimanager.service.OciComputeInstanceService.isValidSshPublicKey(sshKey)) {
                return Map.of("success", false, "error", "SSH 公钥格式无效，请提供合法的 OpenSSH 公钥");
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

            // 🔒 已移除重装前静默跨全区域销毁未挂载引导卷的危险行为，避免误删同账号用户手动分离保留的数据盘！
            // 若遇 200GB 免费配额触顶，由 reinstallInstance 内部配额避让流程仅针对当前实例自身旧盘安全处理。
            ociCloudService.reinstallInstance(ocid, account, instanceName, osName, osVersion, customImageOcid, safeSsh, safeRootPwd);
            ociCloudService.startReinstallMonitor(ocid, account, defaultInstanceName, safeRootPwd, oldBootVolumeId);
            
            return Map.of("success", true, "generatedPwd", (hasPwd ? safeRootPwd : "（通过 SSH 密钥登录）"));
        } catch (IllegalArgumentException | IllegalStateException vex) {
            return Map.of("success", false, "error", vex.getMessage());
        } catch (Exception e) {
            return com.oci.ocimanager.controller.ApiErrors.fail("系统重装失败", e);
        }
    }
}
