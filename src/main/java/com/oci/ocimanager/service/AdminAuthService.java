package com.oci.ocimanager.service;

import com.oci.ocimanager.config.ClientIpResolver;
import com.oci.ocimanager.repository.SystemConfigRepository;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 后台鉴权：验证码、登录锁定、会话签发与口令校验。
 */
@Component
public class AdminAuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int MAX_GLOBAL_CODE_FAIL_ATTEMPTS = 10;
    private static final long CODE_TTL_SECONDS = 180;
    private static final long SEND_COOLDOWN_SECONDS = 60;
    private static final long LOCKOUT_SECONDS = 15 * 60;
    private static final long SESSION_ABSOLUTE_SECONDS = 12 * 60 * 60;
    private static final long SESSION_IDLE_SECONDS = 12 * 60 * 60;
    private static final long STEP_UP_SECONDS = 5 * 60;
    private static final String GLOBAL_CODE_FAIL_KEY = "oci:admin_login:global_code_fail";
    private static final String CODE_HINT = "若已绑定 Telegram，验证码已发送；否则请直接使用管理员密码登录";

    @Autowired private TelegramBotService telegramBotService;
    @Autowired private LocalCacheService redisTemplate;
    @Autowired private SystemConfigRepository systemConfigRepo;
    @Autowired private AccountYamlService accountYamlService;
    @Autowired private Environment environment;

    @Value("${app.admin-username:admin}")
    private String adminUsername;

    // 不用 @Value 读取密码：@Value 会再解析值里面的 ${}，含 $ 或 ${ 的口令会被改掉或让启动失败。
    private String adminPassword = "";

    @Value("${app.admin-id}")
    private String adminId;

    @Value("${app.trust-forwarded-for:false}")
    private boolean trustForwardedFor;

    @PostConstruct
    public void upgradePlaintextAdminPasswords() {
        try {
            reloadAdminPasswordFromEnvironment();
            // 配置文件里的口令是安装脚本写入的那一份。库里的旧口令不能再并列生效，否则改密后旧密码仍能登录。
            if (adminPassword != null && !adminPassword.isBlank()) {
                clearLegacyDatabasePassword();
            } else {
                upgradeDatabasePassword();
            }
            upgradeYamlPassword();
            if (accountYamlService != null) {
                accountYamlService.deleteRememberedAdminPassword();
            }
        } catch (Exception e) {
            System.err.println("管理员密码哈希升级失败: " + e.getMessage());
        }
    }

    public String resolveClientIp(HttpServletRequest request) {
        return ClientIpResolver.resolve(request, trustForwardedFor);
    }

    public Map<String, Object> sendCode(String clientIp) {
        String ip = normalizeIp(clientIp);
        if (isLocked(ip)) {
            return Map.of("success", false, "error", "尝试次数过多，请 " + lockSecondsLeft(ip) + " 秒后再试");
        }
        String cooldownKey = "oci:admin_login:send:" + ip;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            long waitSeconds = Math.max(1, secondsLeft(cooldownKey));
            return Map.of("success", false, "error", "获取过于频繁，请等待 " + waitSeconds + " 秒后再试");
        }
        if (telegramBotService == null || !telegramBotService.isBotRunning()) {
            redisTemplate.opsForValue().set(cooldownKey, "1", SEND_COOLDOWN_SECONDS, TimeUnit.SECONDS);
            return Map.of("success", true, "message", CODE_HINT);
        }
        String codeKey = codeKey(ip);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(codeKey))) {
            redisTemplate.opsForValue().set(cooldownKey, "1", SEND_COOLDOWN_SECONDS, TimeUnit.SECONDS);
            return Map.of("success", true, "message", CODE_HINT);
        }

        String newCode = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
        redisTemplate.opsForValue().set(codeKey, newCode, CODE_TTL_SECONDS, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(cooldownKey, "1", SEND_COOLDOWN_SECONDS, TimeUnit.SECONDS);
        redisTemplate.delete(GLOBAL_CODE_FAIL_KEY);

        telegramBotService.sendText(Long.parseLong(adminId), "🔐 *后台登录验证码*\n\n您的动态密码为：\n`" + newCode + "`\n\n有效期 3 分钟，登录后即刻失效。");
        return Map.of("success", true, "message", CODE_HINT);
    }

    public Map<String, Object> login(String code, String clientIp) {
        return login(null, code, clientIp);
    }

    public Map<String, Object> login(String username, String code, String clientIp) {
        Map<String, Object> confirmed = confirmCredential(username, code, clientIp);
        if (!Boolean.TRUE.equals(confirmed.get("success"))) return confirmed;
        return Map.of("success", true, "token", issueSession());
    }

    public String getConfiguredAdminUsername() {
        String configuredUser = (adminUsername != null && !adminUsername.isBlank()) ? adminUsername.trim() : "admin";
        if (systemConfigRepo != null) {
            var optUser = systemConfigRepo.findById("ADMIN_WEB_USERNAME");
            if (optUser.isPresent() && optUser.get().getConfigValue() != null && !optUser.get().getConfigValue().isBlank()) {
                configuredUser = optUser.get().getConfigValue().trim();
            }
        }
        return configuredUser;
    }

    /**
     * 校验登录凭证（用户名+密码 或 动态码），不签发会话。
     */
    public Map<String, Object> confirmCredential(String username, String secret, String clientIp) {
        return verifyCredentialInternal(username, secret, clientIp, true);
    }

    /**
     * 已登录会话的二次确认（Step-Up）：已持有合法后台会话 Token 时，仅校验管理员密码或动态码，
     * 解决通过 TG 动态码或 Passkey 登录后（前端未录入自定义用户名）绑定新 Passkey 必然失败的缺陷。
     */
    public Map<String, Object> confirmStepUpCredential(String secret, String clientIp) {
        return verifyCredentialInternal(null, secret, clientIp, false);
    }

    private Map<String, Object> verifyCredentialInternal(String username, String secret, String clientIp, boolean requireUsernameForPassword) {
        String ip = normalizeIp(clientIp);
        if (isLocked(ip)) {
            return Map.of("success", false, "error", "错误次数过多，请 " + lockSecondsLeft(ip) + " 秒后重试");
        }

        String current = redisTemplate.opsForValue().get(codeKey(ip));
        if (current == null) current = "";
        String presented = secret == null ? "" : secret.trim();
        boolean codeValid = !current.isEmpty()
                && presented.length() == current.length()
                && MessageDigest.isEqual(
                        presented.getBytes(StandardCharsets.UTF_8),
                        current.getBytes(StandardCharsets.UTF_8));

        String configuredUser = getConfiguredAdminUsername();
        // 🔒 当未登录态使用密码登录时，必须显式提供用户名并与配置的账号名严格匹配；已登录 Step-Up 态则直接校验密码
        boolean usernameValid = !requireUsernameForPassword || ((username != null && !username.isBlank())
                && MessageDigest.isEqual(username.trim().getBytes(StandardCharsets.UTF_8), configuredUser.getBytes(StandardCharsets.UTF_8)));

        boolean passwordValid = usernameValid && passwordMatches(presented);

        if (codeValid || passwordValid) {
            redisTemplate.delete(codeKey(ip));
            clearAuthFailures(ip);
            if (passwordValid) {
                upgradeDatabasePassword();
            }
            return Map.of("success", true);
        }

        if (!current.isEmpty()) {
            Long globalCodeFails = redisTemplate.opsForValue().increment(GLOBAL_CODE_FAIL_KEY);
            if (globalCodeFails != null && globalCodeFails == 1L) {
                redisTemplate.expire(GLOBAL_CODE_FAIL_KEY, CODE_TTL_SECONDS, TimeUnit.SECONDS);
            }
            if (globalCodeFails != null && globalCodeFails >= MAX_GLOBAL_CODE_FAIL_ATTEMPTS) {
                redisTemplate.delete(codeKey(ip));
                redisTemplate.delete(GLOBAL_CODE_FAIL_KEY);
            }
        }

        if (recordAuthFailure(ip)) {
            redisTemplate.delete(codeKey(ip));
            return Map.of("success", false, "error", "错误次数过多，请 " + lockSecondsLeft(ip) + " 秒后重试");
        }
        return Map.of("success", false, "error", requireUsernameForPassword ? "账户名、密码或动态码错误，请重新输入" : "管理员密码或动态码错误，请重新输入");
    }

    public void markStepUp(String token) {
        if (token == null || token.isBlank()) return;
        redisTemplate.opsForValue().set("oci:admin_stepup:" + token.trim(), "1", STEP_UP_SECONDS, TimeUnit.SECONDS);
    }

    public boolean hasStepUp(String token) {
        if (token == null || token.isBlank()) return false;
        return Boolean.TRUE.equals(redisTemplate.hasKey("oci:admin_stepup:" + token.trim()));
    }

    public String issueSession() {
        String token = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        redisTemplate.opsForValue().set(sessionKey(token), Long.toString(now), SESSION_IDLE_SECONDS, TimeUnit.SECONDS);
        return token;
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
        boolean ipLocked = attempts != null && attempts >= MAX_FAILED_ATTEMPTS;
        if (ipLocked) {
            redisTemplate.opsForValue().set(lockKey(ip), "1", LOCKOUT_SECONDS, TimeUnit.SECONDS);
        }
        return ipLocked;
    }

    public void clearAuthFailures(String clientIp) {
        String ip = normalizeIp(clientIp);
        redisTemplate.delete(failKey(ip));
        redisTemplate.delete(lockKey(ip));
    }

    /**
     * 统一提取客户端 Token（优先读取 X-Admin-Token / Authorization 请求头，兜底读取 payload 中的 token）
     */
    public String resolveToken(HttpServletRequest request, Map<String, ?> payload) {
        if (request != null) {
            String token = request.getHeader("X-Admin-Token");
            if (token != null && !token.isBlank()) {
                return token.trim();
            }
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && !authHeader.isBlank()) {
                return authHeader.startsWith("Bearer ") ? authHeader.substring(7).trim() : authHeader.trim();
            }
        }
        if (payload != null && payload.get("token") != null) {
            return String.valueOf(payload.get("token")).trim();
        }
        return "";
    }

    public boolean checkAuth(HttpServletRequest request, Map<String, ?> payload) {
        return checkAuth(resolveToken(request, payload));
    }

    public boolean checkAuth(String token) {
        if (token == null || token.isEmpty()) return false;
        String key = sessionKey(token.trim());
        String raw = redisTemplate.opsForValue().get(key);
        if (raw == null || raw.isBlank()) return false;

        long created;
        try {
            created = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            created = System.currentTimeMillis();
            redisTemplate.opsForValue().set(key, Long.toString(created), SESSION_IDLE_SECONDS, TimeUnit.SECONDS);
        }
        long ageMs = System.currentTimeMillis() - created;
        long absoluteMs = SESSION_ABSOLUTE_SECONDS * 1000L;
        if (ageMs < 0 || ageMs >= absoluteMs) {
            redisTemplate.delete(key);
            redisTemplate.delete("oci:admin_stepup:" + token.trim());
            return false;
        }
        long remainMs = Math.min(SESSION_IDLE_SECONDS * 1000L, absoluteMs - ageMs);
        long remainSeconds = Math.max(1L, remainMs / 1000L);
        redisTemplate.expire(key, remainSeconds, TimeUnit.SECONDS);
        return true;
    }

    public boolean logout(String token) {
        if (token == null || token.isBlank()) return false;
        String trimmed = token.trim();
        redisTemplate.delete("oci:admin_stepup:" + trimmed);
        return Boolean.TRUE.equals(redisTemplate.delete(sessionKey(trimmed)));
    }

    private boolean passwordMatches(String presented) {
        if (presented == null || presented.isEmpty()) return false;
        if (adminPassword != null && !adminPassword.isBlank()) {
            return PasswordHasher.matches(presented, adminPassword.trim());
        }
        if (systemConfigRepo == null) return false;
        var opt = systemConfigRepo.findById("ADMIN_WEB_PASSWORD");
        if (opt.isEmpty() || opt.get().getConfigValue() == null || opt.get().getConfigValue().isBlank()) return false;
        return PasswordHasher.matches(presented, opt.get().getConfigValue().trim());
    }

    /**
     * 取属性源里的原始口令，不走嵌套占位符替换。整段值恰好是 {@code ${...}} 时才交给 Environment，
     * 以兼容包内默认的 {@code ${ADMIN_PASSWORD:}}。
     */
    private void reloadAdminPasswordFromEnvironment() {
        String raw = readRawAdminPassword();
        if (raw.matches("\\$\\{[^}]*}")) {
            try {
                String resolved = environment == null ? "" : environment.getProperty("app.admin-password", "");
                if (resolved != null && !resolved.isBlank() && !resolved.trim().matches("\\$\\{[^}]*}")) {
                    raw = resolved.trim();
                } else {
                    raw = "";
                }
            } catch (Exception ignored) {
                // 解析失败就保留字面量，避免把真正的密码清空。
            }
        }
        this.adminPassword = raw == null ? "" : raw.trim();
    }

    private String readRawAdminPassword() {
        if (!(environment instanceof ConfigurableEnvironment configurable)) return "";
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (source == null || "configurationProperties".equals(source.getName())) continue;
            if (!source.containsProperty("app.admin-password")) continue;
            Object value = source.getProperty("app.admin-password");
            if (value == null) continue;
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private void clearLegacyDatabasePassword() {
        if (systemConfigRepo == null) return;
        if (systemConfigRepo.existsById("ADMIN_WEB_PASSWORD")) {
            systemConfigRepo.deleteById("ADMIN_WEB_PASSWORD");
        }
    }

    private void upgradeDatabasePassword() {
        if (systemConfigRepo == null) return;
        var opt = systemConfigRepo.findById("ADMIN_WEB_PASSWORD");
        if (opt.isEmpty()) return;
        String dbPwd = opt.get().getConfigValue();
        if (dbPwd == null || dbPwd.isBlank() || PasswordHasher.isHashed(dbPwd.trim())) return;
        opt.get().setConfigValue(PasswordHasher.hash(dbPwd.trim()));
        systemConfigRepo.save(opt.get());
    }

    private void upgradeYamlPassword() {
        if (adminPassword == null || adminPassword.isBlank()) return;
        String current = adminPassword.trim();
        if (current.matches("\\$\\{[^}]*}") || PasswordHasher.isHashed(current)) return;
        if (accountYamlService == null) return;
        String hashed = PasswordHasher.hash(current);
        if (accountYamlService.replacePlaintextAdminPassword(current, hashed)) {
            this.adminPassword = hashed;
        }
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
    private static String sessionKey(String token) { return "oci:admin_session:" + token; }

    private boolean isLocked(String ip) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(lockKey(ip)));
    }

    private long lockSecondsLeft(String ip) {
        long ipLeft = Boolean.TRUE.equals(redisTemplate.hasKey(lockKey(ip))) ? secondsLeft(lockKey(ip)) : 0;
        return Math.max(1, ipLeft);
    }

    private long secondsLeft(String key) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl == null || ttl < 0) return 1;
        return ttl;
    }
}
