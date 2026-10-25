package com.oci.ocimanager.controller.admin;

import com.oci.ocimanager.repository.CreationTaskRepository;
import com.oci.ocimanager.service.AdminAuthService;
import com.oci.ocimanager.service.CreationTaskService;
import com.oci.ocimanager.service.OciCloudService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 抢机（批量开机）任务接口：镜像查询、任务启停、日志、SSH 密钥生成。
 * 原属 AdminController，URL 保持不变。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminCreationController {

    @Autowired private OciCloudService ociCloudService;
    @Autowired private CreationTaskService creationTaskService;
    @Autowired private CreationTaskRepository creationTaskRepository;
    @Autowired private AdminAuthService adminAuthService;

    private boolean checkAuth(String token) {
        return adminAuthService.checkAuth(token);
    }

    @PostMapping("/creation/images")
    public Map<String, Object> getAvailableImages(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false);
        try {
            String account = payload.get("account");
            String shape = payload.get("shape");
            String region = payload.get("region");
            if (account == null || shape == null) return Map.of("success", false, "error", "缺少参数");
            
            List<Map<String, String>> images = ociCloudService.getAvailableImages(account, shape, region);
            return Map.of("success", true, "images", images);
        } catch (Exception e) {
            return com.oci.ocimanager.controller.ApiErrors.fail("镜像查询失败", e);
        }
    }

    @PostMapping("/creation/start")
    public Map<String, Object> startCreationTask(@RequestBody Map<String, Object> payload) {
        Object tokenObj = payload.get("token");
        if (tokenObj == null || !checkAuth(String.valueOf(tokenObj))) return Map.of("success", false);
        
        try {
            String account = String.valueOf(payload.get("account"));
            String instanceName = String.valueOf(payload.get("instanceName")); 
            String region = payload.containsKey("region") && payload.get("region") != null ? String.valueOf(payload.get("region")) : null;
            String shape = String.valueOf(payload.get("shape"));
            String osName = String.valueOf(payload.get("osName"));
            String osVersion = String.valueOf(payload.get("osVersion"));
            float cpu = Float.parseFloat(String.valueOf(payload.get("cpu")));
            float ram = Float.parseFloat(String.valueOf(payload.get("ram")));
            long disk = Long.parseLong(String.valueOf(payload.get("disk")));
            int count = Integer.parseInt(String.valueOf(payload.get("count")));
            int interval = Integer.parseInt(String.valueOf(payload.get("interval")));
            boolean ipv4 = Boolean.TRUE.equals(payload.get("ipv4"));
            boolean ipv6 = Boolean.TRUE.equals(payload.get("ipv6"));
            String authType = payload.get("authType") != null ? String.valueOf(payload.get("authType")).trim() : "";
            boolean rootLogin = Boolean.TRUE.equals(payload.get("rootLogin"));
            Object rawRootPwd = payload.get("rootPwd");
            String rootPwd = rawRootPwd != null ? String.valueOf(rawRootPwd).trim() : "";
            Object rawSshKey = payload.get("sshKey");
            String sshKey = rawSshKey != null ? String.valueOf(rawSshKey).trim() : "";

            if ("password".equalsIgnoreCase(authType)) {
                rootLogin = true;
                sshKey = "";
            } else if ("key".equalsIgnoreCase(authType)) {
                rootLogin = false;
                rootPwd = "";
            }

            boolean hasPwd = rootLogin && !rootPwd.isEmpty() && !"null".equalsIgnoreCase(rootPwd);
            boolean hasSsh = !sshKey.isEmpty() && !"null".equalsIgnoreCase(sshKey);
            
            if ("password".equalsIgnoreCase(authType) && !hasPwd) {
                return Map.of("success", false, "error", "请填写或生成 Root 密码！");
            }
            if ("key".equalsIgnoreCase(authType) && !hasSsh) {
                return Map.of("success", false, "error", "请填写或生成 SSH 公钥！");
            }
            if (!hasPwd && !hasSsh) {
                return Map.of("success", false, "error", "必须填写 Root 密码或 SSH 公钥，否则机器创建后将无法登录！");
            }

            String safeRootPwd = hasPwd ? rootPwd : null;
            String safeSsh = hasSsh ? sshKey : null;

            String taskId = creationTaskService.startTask(account, instanceName, region, shape, osName, osVersion, cpu, ram, disk, count, interval, ipv4, ipv6, rootLogin, safeRootPwd, safeSsh);
            return Map.of("success", true, "taskId", taskId);
        } catch (Exception e) {
            return com.oci.ocimanager.controller.ApiErrors.fail("创建任务参数无效", e);
        }
    }

    @PostMapping("/creation/stop")
    public Map<String, Object> stopCreationTask(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false);
        String taskId = payload.get("taskId");
        creationTaskService.stopTask(taskId);
        try {
            creationTaskRepository.deleteById(taskId);
        } catch (Exception ignored) {}
        return Map.of("success", true);
    }

    @PostMapping("/creation/clear-log")
    @Transactional
    public Map<String, Object> clearTaskLog(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false);
        String taskId = payload.get("taskId");
        if (taskId != null) {
            try {
                creationTaskRepository.deleteById(taskId);
            } catch (Exception ignored) {}
        }
        return Map.of("success", true);
    }

    private String formatTaskConfig(String shape, float cpu, float ram, long disk, String osName, String osVersion) {
        StringBuilder sb = new StringBuilder(shape != null ? shape : "未知配置");
        
        if (cpu > 0 || ram > 0 || disk > 0) {
            String c = cpu > 0 ? (cpu == (long)cpu ? String.valueOf((long)cpu) : String.valueOf(cpu)) : "默认";
            String r = ram > 0 ? (ram == (long)ram ? String.valueOf((long)ram) : String.valueOf(ram)) : "默认";
            long d = disk > 0 ? disk : 50L;
            sb.append(String.format(" (%s核/%sG/%dGB)", c, r, d));
        }
        
        if (osName != null && !osName.trim().isEmpty()) {
            sb.append(" | ").append(osName);
            if (osVersion != null && !osVersion.trim().isEmpty()) {
                sb.append(" ").append(osVersion);
            }
        }
        return sb.toString();
    }

    @PostMapping("/creation/list")
    public Map<String, Object> getCreationTasks(@RequestBody Map<String, String> payload) {
        if (!checkAuth(payload.get("token"))) return Map.of("success", false);
        Map<String, Object> tasksView = new HashMap<>();
        
        List<com.oci.ocimanager.entity.CreationTaskRecord> list = creationTaskService.getAllTasks();
        for (com.oci.ocimanager.entity.CreationTaskRecord t : list) {
            String status = t.getStatus();
            boolean isRunning = "RUNNING".equals(status);
            
            Map<String, Object> view = new HashMap<>();
            view.put("account", t.getAccount());
            
            String detailShape = formatTaskConfig(t.getShape(), t.getOcpus(), t.getMemory(), t.getDisk(), t.getOsName(), t.getOsVersion());
            view.put("shape", detailShape);
            
            view.put("targetCount", t.getInstances());
            view.put("successCount", t.getSuccessCount());
            view.put("status", t.getStatus());
            
            String realLog = null;
            if (!isRunning) {
                realLog = t.getLog();
            }
            
            if (realLog == null || realLog.isEmpty()) {
                realLog = creationTaskService.getTaskLog(t.getId()); 
                if (realLog == null || realLog.isEmpty()) {
                    realLog = creationTaskService.getTaskLog(t.getAccount()); 
                }
            }
            
            if (realLog == null || realLog.isEmpty()) {
                realLog = t.getLog(); 
            }
            
            if (realLog == null || realLog.isEmpty()) {
                realLog = "Task started at " + new java.util.Date(t.getStartTime());
            }
            view.put("log", realLog);
            
            view.put("running", isRunning);
            view.put("attemptCount", t.getAttemptCount());
            tasksView.put(t.getId(), view);
        }
        
        return Map.of("success", true, "tasks", tasksView);
    }

    @PostMapping("/creation/generate-key")
    public Map<String, Object> generateSshKey(@RequestBody(required = false) Map<String, String> payload) {
        if (payload == null || !checkAuth(payload.get("token"))) return Map.of("success", false);
        
        try {
            return generateEd25519KeyPair();
        } catch (Exception e) {
            return com.oci.ocimanager.controller.ApiErrors.fail("密钥生成失败", e);
        }
    }

    private Map<String, Object> generateEd25519KeyPair() throws Exception {
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("Ed25519");
        java.security.KeyPair kp = kpg.generateKeyPair();

        byte[] pubKeyEncoded = kp.getPublic().getEncoded();
        byte[] rawPubKey = java.util.Arrays.copyOfRange(pubKeyEncoded, pubKeyEncoded.length - 32, pubKeyEncoded.length);

        byte[] privKeyEncoded = kp.getPrivate().getEncoded();
        byte[] rawPrivSeed = java.util.Arrays.copyOfRange(privKeyEncoded, privKeyEncoded.length - 32, privKeyEncoded.length);

        // 1. 公钥 (OpenSSH wire format)
        java.io.ByteArrayOutputStream pubByteOut = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream pubDataOut = new java.io.DataOutputStream(pubByteOut);
        byte[] keyType = "ssh-ed25519".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        pubDataOut.writeInt(keyType.length);
        pubDataOut.write(keyType);
        pubDataOut.writeInt(rawPubKey.length);
        pubDataOut.write(rawPubKey);
        pubDataOut.flush();
        byte[] pubKeyBytes = pubByteOut.toByteArray();

        String[] keyComments = {
                "admin@workstation", "ops@dev-macbook", "deploy@bastion-01",
                "ubuntu@dev-node", "devops@ci-runner", "user@mbp-local"
        };
        String keyComment = keyComments[java.util.concurrent.ThreadLocalRandom.current().nextInt(keyComments.length)];

        String publicKeyOpenSsh = "ssh-ed25519 " + java.util.Base64.getEncoder().encodeToString(pubKeyBytes) + " " + keyComment;

        // 2. OpenSSH 格式私钥 (BEGIN OPENSSH PRIVATE KEY)
        java.io.ByteArrayOutputStream privOut = new java.io.ByteArrayOutputStream();
        privOut.write("openssh-key-v1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        writeSshString(privOut, "none".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeSshString(privOut, "none".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeSshString(privOut, new byte[0]);
        writeSshInt(privOut, 1);
        writeSshString(privOut, pubKeyBytes);

        java.io.ByteArrayOutputStream privBlock = new java.io.ByteArrayOutputStream();
        int checkInt = new java.security.SecureRandom().nextInt();
        writeSshInt(privBlock, checkInt);
        writeSshInt(privBlock, checkInt);
        writeSshString(privBlock, keyType);
        writeSshString(privBlock, rawPubKey);

        byte[] privAndPub = new byte[64];
        System.arraycopy(rawPrivSeed, 0, privAndPub, 0, 32);
        System.arraycopy(rawPubKey, 0, privAndPub, 32, 32);
        writeSshString(privBlock, privAndPub);
        writeSshString(privBlock, keyComment.getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        int pad = 8 - (privBlock.size() % 8);
        if (pad == 0) pad = 8;
        for (int i = 1; i <= pad; i++) {
            privBlock.write(i);
        }

        writeSshString(privOut, privBlock.toByteArray());

        String openSshPrivPem = "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
                java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privOut.toByteArray()) +
                "\n-----END OPENSSH PRIVATE KEY-----\n";

        return Map.of("success", true, "publicKey", publicKeyOpenSsh, "privateKey", openSshPrivPem, "keyType", "Ed25519");
    }

    private static void writeSshString(java.io.OutputStream out, byte[] data) throws java.io.IOException {
        writeSshInt(out, data.length);
        out.write(data);
    }

    private static void writeSshInt(java.io.OutputStream out, int val) throws java.io.IOException {
        out.write((val >>> 24) & 0xFF);
        out.write((val >>> 16) & 0xFF);
        out.write((val >>> 8) & 0xFF);
        out.write(val & 0xFF);
    }
}
