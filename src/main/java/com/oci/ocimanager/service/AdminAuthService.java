package com.oci.ocimanager.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Component;
import com.oci.ocimanager.repository.SystemConfigRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 后台鉴权组件：验证码签发（TG 推送）、登录校验与失败锁定、会话 Token 校验。
 * 结构重构自 AdminController；登录态字段收拢于此，供拆分后的各 Admin 控制器共享。
 */
@Component
public class AdminAuthService {

    @Autowired private TelegramBotService telegramBotService;
    @Autowired private LocalCacheService redisTemplate;
    @Autowired private SystemConfigRepository systemConfigRepo;

    @Value("${app.admin-password:}")
    private String adminPassword;

    @Value("${app.admin-id}")
    private String adminId;

    // 验证码、失败次数和锁定都按来源 IP 存在 Redis。一个地址输错不会作废其他地址的验证码，也不能把所有人一起锁住。
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int MAX_GLOBAL_CODE_FAIL_ATTEMPTS = 10;
    private static final long CODE_TTL_SECONDS = 180;
    private static final long SEND_COOLDOWN_SECONDS = 60;
    private static final long LOCKOUT_SECONDS = 15 * 60;
    private static final String GLOBAL_CODE_FAIL_KEY = "oci:admin_login:global_code_fail";

    public Map<String, Object> sendCode(String clientIp) {
        if (telegramBotService == null || !telegramBotService.isBotRunning()) {
            return Map.of("success", false, "error", "Telegram 机器人未配置或未启用，请直接使用部署时设置的管理员密码登录");
        }
        String ip = normalizeIp(clientIp);
        if (isLocked(ip)) {
            return Map.of("success", false, "error", "尝试次数过多，请 " + lockSecondsLeft(ip) + " 秒后再试");
        }
        String cooldownKey = "oci:admin_login:send:" + ip;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            long waitSeconds = Math.max(1, secondsLeft(cooldownKey));
            return Map.of("success", false, "error", "获取过于频繁，请等待 " + waitSeconds + " 秒后再试");
        }
        String codeKey = codeKey(ip);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(codeKey))) {
            redisTemplate.opsForValue().set(cooldownKey, "1", SEND_COOLDOWN_SECONDS, TimeUnit.SECONDS);
            return Map.of("success", true, "message", "验证码仍在有效期内，请查看 Telegram");
        }

        String newCode = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        redisTemplate.opsForValue().set(codeKey, newCode, CODE_TTL_SECONDS, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(cooldownKey, "1", SEND_COOLDOWN_SECONDS, TimeUnit.SECONDS);
        redisTemplate.delete(GLOBAL_CODE_FAIL_KEY);

        telegramBotService.sendText(Long.parseLong(adminId), "🔐 *后台登录验证码*\n\n您的动态密码为：\n`" + newCode + "`\n\n有效期 3 分钟，登录后即刻失效。");
        return Map.of("success", true);
    }

    public Map<String, Object> login(String code, String clientIp) {
        String ip = normalizeIp(clientIp);
        if (isLocked(ip)) {
            return Map.of("success", false, "error", "错误次数过多，请 " + lockSecondsLeft(ip) + " 秒后重试");
        }

        String current = redisTemplate.opsForValue().get(codeKey(ip));
        if (current == null) current = "";
        String presented = code == null ? "" : code.trim();
        boolean codeValid = !current.isEmpty()
                && presented.length() == current.length()
                && MessageDigest.isEqual(
                        presented.getBytes(StandardCharsets.UTF_8),
                        current.getBytes(StandardCharsets.UTF_8));

        boolean passwordValid = false;
        if (adminPassword != null && !adminPassword.isBlank()) {
            passwordValid = MessageDigest.isEqual(
                    presented.getBytes(StandardCharsets.UTF_8),
                    adminPassword.trim().getBytes(StandardCharsets.UTF_8));
        }
        if (!passwordValid && systemConfigRepo != null) {
            var opt = systemConfigRepo.findById("ADMIN_WEB_PASSWORD");
            if (opt.isPresent() && opt.get().getConfigValue() != null && !opt.get().getConfigValue().isBlank()) {
                String dbPwd = opt.get().getConfigValue().trim();
                passwordValid = MessageDigest.isEqual(
                        presented.getBytes(StandardCharsets.UTF_8),
                        dbPwd.getBytes(StandardCharsets.UTF_8));
            }
        }

        if (codeValid || passwordValid) {
            redisTemplate.delete(codeKey(ip));
            redisTemplate.delete(failKey(ip));
            redisTemplate.delete(lockKey(ip));
            redisTemplate.delete(GLOBAL_CODE_FAIL_KEY);
            String token = UUID.randomUUID().toString();
            redisTemplate.opsForValue().set("oci:admin_session:" + token, "1", 24, TimeUnit.HOURS);
            return Map.of("success", true, "token", token);
        }

        if (!current.isEmpty()) {
            Long globalFails = redisTemplate.opsForValue().increment(GLOBAL_CODE_FAIL_KEY);
            if (globalFails != null && globalFails == 1L) {
                redisTemplate.expire(GLOBAL_CODE_FAIL_KEY, CODE_TTL_SECONDS, TimeUnit.SECONDS);
            }
            if (globalFails != null && globalFails >= MAX_GLOBAL_CODE_FAIL_ATTEMPTS) {
                redisTemplate.delete(codeKey(ip));
                redisTemplate.delete(GLOBAL_CODE_FAIL_KEY);
            }
        }

        if (recordAuthFailure(ip)) {
            redisTemplate.delete(codeKey(ip));
            return Map.of("success", false, "error", "错误次数过多，该来源已锁定 15 分钟");
        }
        return Map.of("success", false, "error", "密码或动态码错误，请重新输入");
    }

    public boolean isClientIpLocked(String clientIp) {
        return isLocked(normalizeIp(clientIp));
    }

    public long getLockSecondsLeft(String clientIp) {
        return lockSecondsLeft(normalizeIp(clientIp));
    }

    public boolean recordAuthFailure(String clientIp) {
        String ip = normalizeIp(clientIp);
        Long attempts = redisTemplate.opsForValue().increment(failKey(ip));
        if (attempts != null && attempts == 1L) {
            redisTemplate.expire(failKey(ip), LOCKOUT_SECONDS, TimeUnit.SECONDS);
        }
        if (attempts != null && attempts >= MAX_FAILED_ATTEMPTS) {
            redisTemplate.opsForValue().set(lockKey(ip), "1", LOCKOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        }
        return false;
    }

    public void clearAuthFailures(String clientIp) {
        String ip = normalizeIp(clientIp);
        redisTemplate.delete(failKey(ip));
        redisTemplate.delete(lockKey(ip));
    }

    private static String normalizeIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank() || "UNKNOWN".equalsIgnoreCase(clientIp)) return "unknown";
        String ip = clientIp.trim();
        if (ip.length() > 45) return "unknown";
        for (int i = 0; i < ip.length(); i++) {
            char c = ip.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '.' || c == ':';
            if (!ok) return "unknown";
        }
        return ip;
    }

    private static String codeKey(String ip) { return "oci:admin_login:code:" + ip; }
    private static String failKey(String ip) { return "oci:admin_login:fail:" + ip; }
    private static String lockKey(String ip) { return "oci:admin_login:lock:" + ip; }

    private boolean isLocked(String ip) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(lockKey(ip)));
    }

    private long lockSecondsLeft(String ip) {
        return Math.max(1, secondsLeft(lockKey(ip)));
    }

    private long secondsLeft(String key) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl < 0) return 1;
        return ttl;
    }

    public boolean checkAuth(String token) {
        if (token == null || token.isEmpty()) return false;
        Boolean isValid = redisTemplate.hasKey("oci:admin_session:" + token);
        if (Boolean.TRUE.equals(isValid)) {
            redisTemplate.expire("oci:admin_session:" + token, 24, TimeUnit.HOURS); 
            return true;
        }
        return false;
    }

    public boolean logout(String token) {
        if (token == null || token.isBlank()) return false;
        return Boolean.TRUE.equals(redisTemplate.delete("oci:admin_session:" + token.trim()));
    }
}
