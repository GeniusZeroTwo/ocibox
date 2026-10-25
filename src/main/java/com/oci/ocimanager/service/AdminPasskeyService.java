package com.oci.ocimanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oci.ocimanager.config.ClientIpResolver;
import com.oci.ocimanager.entity.AdminPasskey;
import com.oci.ocimanager.repository.AdminPasskeyRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 🔐 管理员通行密钥 (Passkey / WebAuthn FIDO2) 核心业务服务
 */
@Service
public class AdminPasskeyService {

    private static final Logger LOG = Logger.getLogger(AdminPasskeyService.class.getName());
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired private AdminPasskeyRepository adminPasskeyRepository;
    @Autowired private AdminAuthService adminAuthService;
    @Autowired private TelegramBotService telegramBotService;
    @Autowired private LocalCacheService redisTemplate;
    @Autowired private ObjectMapper objectMapper;

    /**
     * 生成注册选项 (WebAuthn Creation Options)
     */
    public Map<String, Object> generateRegisterOptions(String token, String rpId) {
        if (!adminAuthService.checkAuth(token)) {
            return Map.of("success", false, "error", "首次添加通行密钥需要在 TG 登录后台后方可添加，请先登录！");
        }

        byte[] challengeBytes = new byte[32];
        SECURE_RANDOM.nextBytes(challengeBytes);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes);

        // 将 challenge 与 rpId 存入 Redis，有效期 2 分钟
        redisTemplate.opsForValue().set("oci:passkey:reg_challenge:" + token, challenge, 2, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set("oci:passkey:reg_rpid:" + token, rpId != null ? rpId.trim() : "", 2, TimeUnit.MINUTES);

        // 已存在的凭证排除列表，防止同一设备重复注册
        List<AdminPasskey> existingList = adminPasskeyRepository.findAll();
        List<Map<String, Object>> excludeList = new ArrayList<>();
        for (AdminPasskey p : existingList) {
            excludeList.add(Map.of("id", p.getCredentialId(), "type", "public-key"));
        }

        Map<String, Object> rp = new HashMap<>();
        rp.put("name", "OCI Manager Pro");
        if (rpId != null && !rpId.isBlank() && !"localhost".equalsIgnoreCase(rpId)) {
            rp.put("id", rpId.trim());
        }

        Map<String, Object> user = Map.of(
                "id", Base64.getUrlEncoder().withoutPadding().encodeToString("admin".getBytes(StandardCharsets.UTF_8)),
                "name", "admin",
                "displayName", "系统管理员"
        );

        List<Map<String, Object>> pubKeyCredParams = List.of(
                Map.of("type", "public-key", "alg", -7),   // ES256 (ECDSA P-256)
                Map.of("type", "public-key", "alg", -257)  // RS256 (RSA)
        );

        Map<String, Object> authenticatorSelection = Map.of(
                "residentKey", "preferred",
                "requireResidentKey", false,
                "userVerification", "preferred"
        );

        return Map.of(
                "success", true,
                "rp", rp,
                "user", user,
                "challenge", challenge,
                "pubKeyCredParams", pubKeyCredParams,
                "authenticatorSelection", authenticatorSelection,
                "timeout", 60000,
                "attestation", "none",
                "excludeCredentials", excludeList
        );
    }

    /**
     * 校验并保存新注册的通行密钥 (重载兼容)
     */
    public Map<String, Object> verifyAndSaveRegistration(String token, Map<String, Object> body) {
        return verifyAndSaveRegistration(token, body, null);
    }

    /**
     * 校验并保存新注册的通行密钥 (含 Origin 与 RP 安全校验)
     */
    public Map<String, Object> verifyAndSaveRegistration(String token, Map<String, Object> body, HttpServletRequest request) {
        if (!adminAuthService.checkAuth(token)) {
            return Map.of("success", false, "error", "首次添加通行密钥需要在 TG 登录后台后方可添加，请先登录！");
        }

        String expectedChallenge = redisTemplate.opsForValue().get("oci:passkey:reg_challenge:" + token);
        String expectedRpId = redisTemplate.opsForValue().get("oci:passkey:reg_rpid:" + token);
        if (expectedChallenge == null || expectedChallenge.isBlank()) {
            return Map.of("success", false, "error", "注册超时或无效，请重新尝试添加通行密钥");
        }
        redisTemplate.delete("oci:passkey:reg_challenge:" + token);
        redisTemplate.delete("oci:passkey:reg_rpid:" + token);

        try {
            String clientDataJsonStr = (String) body.get("clientDataJSON");
            String credentialId = (String) body.get("credentialId");
            String publicKeySpkiStr = (String) body.get("publicKeySpki");
            String attestationObjectStr = (String) body.get("attestationObject");
            String name = (String) body.get("name");
            Integer algorithm = body.get("algorithm") instanceof Number n ? n.intValue() : -7;

            if (credentialId == null || credentialId.isBlank()) {
                return Map.of("success", false, "error", "未收到有效的凭证 ID");
            }
            if (attestationObjectStr == null || attestationObjectStr.isBlank()) {
                return Map.of("success", false, "error", "未提供 attestationObject");
            }
            if (expectedRpId == null || expectedRpId.isBlank()) {
                return Map.of("success", false, "error", "注册域缺失，请重新尝试添加通行密钥");
            }

            // 1. 校验 clientDataJSON
            byte[] clientDataBytes = decodeBase64OrUrl(clientDataJsonStr);
            JsonNode clientDataNode = objectMapper.readTree(clientDataBytes);
            String type = clientDataNode.path("type").asText();
            String challenge = clientDataNode.path("challenge").asText();
            String origin = clientDataNode.path("origin").asText();

            if (!"webauthn.create".equals(type)) {
                return Map.of("success", false, "error", "客户端认证类型异常: " + type);
            }
            if (!expectedChallenge.equals(challenge)) {
                return Map.of("success", false, "error", "Challenge 校验失败");
            }
            if (!verifyOrigin(origin, expectedRpId, request)) {
                return Map.of("success", false, "error", "WebAuthn 注册来源非法 (Origin 不匹配: " + origin + ")");
            }

            // 2. 公钥和凭证 ID 只从 attestationObject.authData 解析，客户端字段必须与之一致
            byte[] authData = extractAuthData(decodeBase64OrUrl(attestationObjectStr));
            if (authData == null || authData.length < 37) {
                return Map.of("success", false, "error", "无法解析认证器数据");
            }
            if (!rpIdHashMatches(Arrays.copyOfRange(authData, 0, 32), expectedRpId)) {
                return Map.of("success", false, "error", "WebAuthn 注册域校验失败 (rpIdHash 不匹配)");
            }
            int authFlags = authData[32] & 0xFF;
            if ((authFlags & 0x01) == 0) {
                return Map.of("success", false, "error", "未检测到用户确认交互 (User Present)");
            }
            if ((authFlags & 0x40) == 0) {
                return Map.of("success", false, "error", "认证器未携带可绑定的公钥");
            }
            if (authData.length < 55) {
                return Map.of("success", false, "error", "认证器数据长度异常");
            }
            int credIdLen = ((authData[53] & 0xFF) << 8) | (authData[54] & 0xFF);
            if (credIdLen <= 0 || credIdLen > 1023 || 55 + credIdLen > authData.length) {
                return Map.of("success", false, "error", "凭证 ID 长度异常");
            }
            byte[] credIdBytes = Arrays.copyOfRange(authData, 55, 55 + credIdLen);
            byte[] clientCredId = decodeBase64OrUrl(credentialId);
            if (clientCredId.length == 0 || !MessageDigest.isEqual(credIdBytes, clientCredId)) {
                return Map.of("success", false, "error", "凭证 ID 与认证器数据不一致");
            }
            if (algorithm != -7) {
                return Map.of("success", false, "error", "仅接受 ES256 通行密钥");
            }
            PublicKey authKey = parseEs256CoseKey(authData, 55 + credIdLen);
            if (authKey == null) {
                return Map.of("success", false, "error", "仅接受认证器内的 ES256 公钥");
            }
            byte[] canonicalSpki = authKey.getEncoded();
            if (publicKeySpkiStr != null && !publicKeySpkiStr.isBlank()) {
                byte[] clientSpki = decodeBase64OrUrl(publicKeySpkiStr);
                PublicKey clientKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(clientSpki));
                if (!MessageDigest.isEqual(canonicalSpki, clientKey.getEncoded())) {
                    return Map.of("success", false, "error", "客户端公钥与认证器数据不一致");
                }
            }
            String base64Spki = Base64.getEncoder().encodeToString(canonicalSpki);
            credentialId = Base64.getUrlEncoder().withoutPadding().encodeToString(credIdBytes);
            algorithm = -7;

            // 3. 检查凭证是否已被注册
            if (adminPasskeyRepository.findByCredentialId(credentialId).isPresent()) {
                return Map.of("success", false, "error", "该通行密钥已在系统中绑定，无需重复添加");
            }

            String finalName = (name != null && !name.trim().isEmpty()) ? name.trim() : "通行密钥 #" + (adminPasskeyRepository.count() + 1);

            AdminPasskey passkey = new AdminPasskey();
            passkey.setCredentialId(credentialId);
            passkey.setPublicKeySpki(base64Spki);
            passkey.setAlgorithm(algorithm);
            passkey.setName(finalName);
            passkey.setSignCount(0L);
            passkey.setCreatedAt(LocalDateTime.now().format(TIME_FORMATTER));

            adminPasskeyRepository.save(passkey);
            LOG.info("✅ 管理员成功注册新通行密钥: " + finalName);

            return Map.of("success", true, "message", "通行密钥 [" + finalName + "] 绑定成功！");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "注册通行密钥异常", e);
            return Map.of("success", false, "error", "添加失败: " + e.getMessage());
        }
    }

    /**
     * 生成登录认证选项 (WebAuthn Request Options)
     */
    public Map<String, Object> generateLoginOptions(String rpId) {
        return generateLoginOptions(rpId, null);
    }

    public Map<String, Object> generateLoginOptions(String rpId, HttpServletRequest request) {
        String clientIp = resolveClientIp(request);
        if (adminAuthService.isClientIpLocked(clientIp)) {
            return Map.of("success", false, "error", "错误次数过多，请 " + adminAuthService.getLockSecondsLeft(clientIp) + " 秒后再试");
        }
        String rateKey = "oci:passkey:opt_rate:" + clientIp;
        Long reqCount = redisTemplate.opsForValue().increment(rateKey);
        if (reqCount != null && reqCount == 1L) {
            redisTemplate.expire(rateKey, 60, TimeUnit.SECONDS);
        }
        if (reqCount != null && reqCount > 15L) {
            return Map.of("success", false, "error", "请求过于频繁，请稍后再试");
        }

        List<AdminPasskey> passkeys = adminPasskeyRepository.findAll();
        if (passkeys.isEmpty()) {
            return Map.of("success", false, "error", "当前未绑定任何通行密钥。首次添加通行密钥需要在 TG 登录后台后方可添加，请先使用 Telegram 动态验证码登录！");
        }

        byte[] challengeBytes = new byte[32];
        SECURE_RANDOM.nextBytes(challengeBytes);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes);
        String challengeId = UUID.randomUUID().toString();

        // 存入 Redis，有效期 2 分钟
        redisTemplate.opsForValue().set("oci:passkey:login_challenge:" + challengeId, challenge, 2, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set("oci:passkey:login_rpid:" + challengeId, rpId != null ? rpId.trim() : "", 2, TimeUnit.MINUTES);

        List<Map<String, Object>> allowCredentials = new ArrayList<>();
        for (AdminPasskey p : passkeys) {
            allowCredentials.add(Map.of("id", p.getCredentialId(), "type", "public-key"));
        }

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("challengeId", challengeId);
        res.put("challenge", challenge);
        res.put("timeout", 60000);
        res.put("userVerification", "preferred");
        res.put("allowCredentials", allowCredentials);
        if (rpId != null && !rpId.isBlank() && !"localhost".equalsIgnoreCase(rpId)) {
            res.put("rpId", rpId.trim());
        }

        return res;
    }

    /**
     * 校验通行密钥登录签名并签发后台会话，同时向管理员 Telegram 发送安全通知
     */
    public Map<String, Object> verifyLogin(Map<String, Object> body, HttpServletRequest request) {
        String clientIp = resolveClientIp(request);
        if (adminAuthService.isClientIpLocked(clientIp)) {
            return Map.of("success", false, "error", "错误次数过多，请 " + adminAuthService.getLockSecondsLeft(clientIp) + " 秒后再试");
        }

        String challengeId = (String) body.get("challengeId");
        if (challengeId == null || challengeId.isBlank()) {
            return Map.of("success", false, "error", "缺少 challengeId 参数");
        }

        String expectedChallenge = redisTemplate.opsForValue().get("oci:passkey:login_challenge:" + challengeId);
        String expectedRpId = redisTemplate.opsForValue().get("oci:passkey:login_rpid:" + challengeId);
        if (expectedChallenge == null || expectedChallenge.isBlank()) {
            return Map.of("success", false, "error", "登录验证已超时，请重新点击登录");
        }
        redisTemplate.delete("oci:passkey:login_challenge:" + challengeId);
        redisTemplate.delete("oci:passkey:login_rpid:" + challengeId);

        try {
            String credentialId = (String) body.get("credentialId");
            String clientDataJsonStr = (String) body.get("clientDataJSON");
            String authenticatorDataStr = (String) body.get("authenticatorData");
            String signatureStr = (String) body.get("signature");

            if (credentialId == null || clientDataJsonStr == null || authenticatorDataStr == null || signatureStr == null) {
                return Map.of("success", false, "error", "凭证认证参数不完整");
            }

            // 1. 查找数据库中注册的凭证
            Optional<AdminPasskey> opt = adminPasskeyRepository.findByCredentialId(credentialId);
            if (opt.isEmpty()) {
                adminAuthService.recordAuthFailure(clientIp);
                return Map.of("success", false, "error", "该通行密钥在服务端未找到，可能已被解绑");
            }
            AdminPasskey passkey = opt.get();

            // 2. 校验 clientDataJSON
            byte[] clientDataBytes = decodeBase64OrUrl(clientDataJsonStr);
            JsonNode clientDataNode = objectMapper.readTree(clientDataBytes);
            String type = clientDataNode.path("type").asText();
            String challenge = clientDataNode.path("challenge").asText();
            String origin = clientDataNode.path("origin").asText();

            if (!"webauthn.get".equals(type)) {
                adminAuthService.recordAuthFailure(clientIp);
                return Map.of("success", false, "error", "认证动作类型异常: " + type);
            }
            if (!expectedChallenge.equals(challenge)) {
                adminAuthService.recordAuthFailure(clientIp);
                return Map.of("success", false, "error", "Challenge 不匹配");
            }
            if (!verifyOrigin(origin, expectedRpId, request)) {
                adminAuthService.recordAuthFailure(clientIp);
                return Map.of("success", false, "error", "WebAuthn 认证来源非法 (Origin 不匹配: " + origin + ")");
            }

            // 3. 校验 authenticatorData 标志位 (UP 用户在场位) 与 rpIdHash
            byte[] authDataBytes = decodeBase64OrUrl(authenticatorDataStr);
            if (authDataBytes.length < 37) {
                adminAuthService.recordAuthFailure(clientIp);
                return Map.of("success", false, "error", "认证器数据长度异常");
            }

            // 🛡️ WebAuthn §7.2 Step 11: 校验 rpIdHash (authDataBytes[0..31])
            if (expectedRpId == null || expectedRpId.isBlank()
                    || !rpIdHashMatches(Arrays.copyOfRange(authDataBytes, 0, 32), expectedRpId)) {
                LOG.warning("⚠️ WebAuthn rpIdHash 校验失败，预期 RP: " + expectedRpId);
                adminAuthService.recordAuthFailure(clientIp);
                return Map.of("success", false, "error", "WebAuthn 认证域校验失败 (rpIdHash 不匹配)");
            }

            byte flags = authDataBytes[32];
            if ((flags & 0x01) == 0) {
                adminAuthService.recordAuthFailure(clientIp);
                return Map.of("success", false, "error", "未检测到用户确认交互 (User Present)");
            }

            // 4. 密码学数字签名验证: Signature over (authData || SHA256(clientDataJSON))
            byte[] clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataBytes);
            byte[] signedData = new byte[authDataBytes.length + clientDataHash.length];
            System.arraycopy(authDataBytes, 0, signedData, 0, authDataBytes.length);
            System.arraycopy(clientDataHash, 0, signedData, authDataBytes.length, clientDataHash.length);

            byte[] sigBytes = decodeBase64OrUrl(signatureStr);
            byte[] derSig = ensureDerSignature(sigBytes);

            byte[] spkiBytes = Base64.getDecoder().decode(passkey.getPublicKeySpki());
            String keyType = (passkey.getAlgorithm() != null && passkey.getAlgorithm() == -257) ? "RSA" : "EC";
            KeyFactory kf = KeyFactory.getInstance(keyType);
            PublicKey publicKey = kf.generatePublic(new X509EncodedKeySpec(spkiBytes));

            String sigAlgorithm = keyType.equals("RSA") ? "SHA256withRSA" : "SHA256withECDSA";
            Signature sig = Signature.getInstance(sigAlgorithm);
            sig.initVerify(publicKey);
            sig.update(signedData);

            if (!sig.verify(derSig)) {
                LOG.warning("⚠️ 通行密钥签名验证失败，密钥名称: " + passkey.getName());
                adminAuthService.recordAuthFailure(clientIp);
                sendPasskeyFailedTgAlert(passkey, request, "数字签名校验未通过 (可能存在伪造签名或重放攻击)");
                return Map.of("success", false, "error", "通行密钥数字签名校验未通过");
            }

            // 5. 校验通过，更新最后使用时间与 signCount (含硬件凭据克隆与重放检测)
            long signCount = ((authDataBytes[33] & 0xFFL) << 24)
                    | ((authDataBytes[34] & 0xFFL) << 16)
                    | ((authDataBytes[35] & 0xFFL) << 8)
                    | (authDataBytes[36] & 0xFFL);

            // 🛡️ WebAuthn §7.2 Step 17: 硬件凭据克隆与重放检测
            long currentSignCount = passkey.getSignCount() != null ? passkey.getSignCount() : 0L;
            if (currentSignCount > 0 && signCount > 0 && signCount <= currentSignCount) {
                LOG.warning("🚨 [安全告警] 通行密钥签名计数器异常 (current=" + currentSignCount + ", received=" + signCount + ")，疑似硬件凭据克隆攻击！");
                adminAuthService.recordAuthFailure(clientIp);
                sendPasskeyFailedTgAlert(passkey, request, "签名计数器异常 (疑似克隆攻击或重放)");
                return Map.of("success", false, "error", "安全拦截：通行密钥计数器异常，可能存在克隆风险");
            }
            adminAuthService.clearAuthFailures(clientIp);
            passkey.setSignCount(signCount);
            String nowStr = LocalDateTime.now().format(TIME_FORMATTER);
            passkey.setLastUsedAt(nowStr);
            adminPasskeyRepository.save(passkey);

            // 6. 签发后台管理员会话 Token (与常规验证码登录保持统一)
            String token = UUID.randomUUID().toString();
            redisTemplate.opsForValue().set("oci:admin_session:" + token, "1", 24, TimeUnit.HOURS);

            // 7. 📲 向管理员 Telegram 推送登录通知
            sendPasskeyLoginTgNotification(passkey, request, nowStr);

            LOG.info("🎉 管理员使用通行密钥 [" + passkey.getName() + "] 成功登录后台！");
            return Map.of(
                    "success", true,
                    "token", token,
                    "name", passkey.getName()
            );

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "通行密钥登录校验异常", e);
            return Map.of("success", false, "error", "验证异常: " + e.getMessage());
        }
    }

    /**
     * 发送 Telegram 安全通知
     */
    private void sendPasskeyLoginTgNotification(AdminPasskey passkey, HttpServletRequest request, String loginTime) {
        try {
            String clientIp = resolveClientIp(request);
            String ua = request != null ? request.getHeader("User-Agent") : "";
            String deviceInfo = parseSimpleUserAgent(ua);

            String tgMsg = "🔐 <b>【安全通知】管理员通行密钥登录</b>\n\n"
                    + "👤 <b>登录身份：</b>系统管理员\n"
                    + "🔑 <b>使用密钥：</b><code>" + escapeHtml(passkey.getName()) + "</code>\n"
                    + "⏰ <b>登录时间：</b><code>" + loginTime + "</code>\n"
                    + "🌐 <b>来源 IP：</b><code>" + clientIp + "</code>\n"
                    + "🖥️ <b>设备环境：</b><code>" + escapeHtml(deviceInfo) + "</code>\n\n"
                    + "🛡️ <i>系统已签发管理控制台授权令牌。如非本人操作，请立刻登录后台注销该通行密钥！</i>";

            telegramBotService.sendHtmlMessage(tgMsg);
        } catch (Exception e) {
            LOG.warning("⚠️ 发送通行密钥登录 TG 通知失败: " + e.getMessage());
        }
    }

    /**
     * 🚨 发送 Telegram 通行密钥认证失败安全预警 (带 5 分钟单 IP 冷却，防止恶意轰炸管理员 TG)
     */
    private void sendPasskeyFailedTgAlert(AdminPasskey passkey, HttpServletRequest request, String reason) {
        try {
            String clientIp = resolveClientIp(request);
            String alertCooldownKey = "oci:passkey:fail_alert_cooldown:" + clientIp;
            if (Boolean.TRUE.equals(redisTemplate.hasKey(alertCooldownKey))) {
                return;
            }
            redisTemplate.opsForValue().set(alertCooldownKey, "1", 5, TimeUnit.MINUTES);

            String ua = request != null ? request.getHeader("User-Agent") : "";
            String deviceInfo = parseSimpleUserAgent(ua);
            String nowStr = LocalDateTime.now().format(TIME_FORMATTER);

            String tgMsg = "🚨 <b>【安全预警】通行密钥登录失败拦截</b>\n\n"
                    + "🔑 <b>目标密钥：</b><code>" + (passkey != null ? escapeHtml(passkey.getName()) : "未知") + "</code>\n"
                    + "⚠️ <b>失败原因：</b>" + escapeHtml(reason) + "\n"
                    + "⏰ <b>拦截时间：</b><code>" + nowStr + "</code>\n"
                    + "🌐 <b>来源 IP：</b><code>" + escapeHtml(clientIp) + "</code>\n"
                    + "🖥️ <b>设备环境：</b><code>" + escapeHtml(deviceInfo) + "</code>\n\n"
                    + "🛡️ <i>系统已强制阻断本次认证请求，未签发会话令牌。如非本人操作，请密切留意系统安全！</i>";

            telegramBotService.sendHtmlMessage(tgMsg);
        } catch (Exception e) {
            LOG.warning("⚠️ 发送通行密钥失败告警 TG 通知失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有通行密钥列表 (仅限管理员)
     */
    public Map<String, Object> listPasskeys(String token) {
        if (!adminAuthService.checkAuth(token)) {
            return Map.of("success", false, "error", "鉴权失败，请重新登录");
        }
        List<AdminPasskey> list = adminPasskeyRepository.findAllByOrderByCreatedAtDesc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AdminPasskey p : list) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", p.getId());
            item.put("name", p.getName());
            item.put("credentialId", p.getCredentialId());
            item.put("algorithm", p.getAlgorithm());
            item.put("createdAt", p.getCreatedAt());
            item.put("lastUsedAt", p.getLastUsedAt());
            result.add(item);
        }
        return Map.of("success", true, "list", result);
    }

    /**
     * 删除通行密钥
     */
    public Map<String, Object> deletePasskey(String token, Long id) {
        if (!adminAuthService.checkAuth(token)) {
            return Map.of("success", false, "error", "鉴权失败，请重新登录");
        }
        if (id == null) {
            return Map.of("success", false, "error", "缺少密钥 ID");
        }
        Optional<AdminPasskey> opt = adminPasskeyRepository.findById(id);
        if (opt.isEmpty()) {
            return Map.of("success", false, "error", "密钥不存在");
        }
        String name = opt.get().getName();
        adminPasskeyRepository.deleteById(id);
        LOG.info("🗑️ 管理员删除了通行密钥: " + name);
        return Map.of("success", true, "message", "通行密钥 [" + name + "] 已成功移除");
    }

    /**
     * 重命名通行密钥
     */
    public Map<String, Object> renamePasskey(String token, Long id, String newName) {
        if (!adminAuthService.checkAuth(token)) {
            return Map.of("success", false, "error", "鉴权失败，请重新登录");
        }
        if (id == null || newName == null || newName.trim().isEmpty()) {
            return Map.of("success", false, "error", "参数不合法");
        }
        Optional<AdminPasskey> opt = adminPasskeyRepository.findById(id);
        if (opt.isEmpty()) {
            return Map.of("success", false, "error", "密钥不存在");
        }
        AdminPasskey p = opt.get();
        p.setName(newName.trim());
        adminPasskeyRepository.save(p);
        return Map.of("success", true, "message", "重命名成功");
    }

    // ==========================================
    // 🛠️ 辅助编解码与网络解析工具方法
    // ==========================================

    private byte[] decodeBase64OrUrl(String input) {
        if (input == null || input.isBlank()) return new byte[0];
        String clean = input.trim();
        try {
            return Base64.getUrlDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            return Base64.getDecoder().decode(clean);
        }
    }

    /**
     * 确保 ECDSA 签名为 ASN.1 DER 格式 (若 authenticator 抛出 IEEE P1363 64 字节 raw r||s 则转换)
     */
    private byte[] ensureDerSignature(byte[] sig) {
        if (sig == null || sig.length == 0) return sig;
        if (sig[0] == 0x30) return sig; // 已是 DER SEQUENCE
        if (sig.length == 64) {
            byte[] r = Arrays.copyOfRange(sig, 0, 32);
            byte[] s = Arrays.copyOfRange(sig, 32, 64);
            BigInteger rInt = new BigInteger(1, r);
            BigInteger sInt = new BigInteger(1, s);
            byte[] rBytes = rInt.toByteArray();
            byte[] sBytes = sInt.toByteArray();

            int totalLen = 2 + rBytes.length + 2 + sBytes.length;
            byte[] der = new byte[2 + totalLen];
            der[0] = 0x30;
            der[1] = (byte) totalLen;
            der[2] = 0x02;
            der[3] = (byte) rBytes.length;
            System.arraycopy(rBytes, 0, der, 4, rBytes.length);

            int sOffset = 4 + rBytes.length;
            der[sOffset] = 0x02;
            der[sOffset + 1] = (byte) sBytes.length;
            System.arraycopy(sBytes, 0, der, sOffset + 2, sBytes.length);
            return der;
        }
        return sig;
    }

    private byte[] extractAuthData(byte[] attestationObject) {
        try {
            CborCursor cursor = new CborCursor(attestationObject, 0);
            if (cursor.major() != 5) return null;
            long pairs = cursor.readArg();
            if (pairs <= 0 || pairs > 8) return null;
            for (long i = 0; i < pairs; i++) {
                if (cursor.major() == 3) {
                    String key = cursor.readText();
                    if ("authData".equals(key)) {
                        if (cursor.major() != 2) return null;
                        return cursor.readBytes();
                    }
                    cursor.skip();
                } else {
                    cursor.skip();
                    cursor.skip();
                }
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 只接受 COSE EC2 / P-256 / ES256。解析失败返回 null，不回退到客户端自报的 SPKI。 */
    private PublicKey parseEs256CoseKey(byte[] authData, int offset) {
        try {
            CborCursor cursor = new CborCursor(authData, offset);
            if (cursor.major() != 5) return null;
            long pairs = cursor.readArg();
            if (pairs < 4 || pairs > 16) return null;
            Integer kty = null;
            Integer alg = null;
            Integer crv = null;
            byte[] x = null;
            byte[] y = null;
            for (long i = 0; i < pairs; i++) {
                int keyMajor = cursor.major();
                long keyArg = cursor.readArg();
                int label;
                if (keyMajor == 0) label = Math.toIntExact(keyArg);
                else if (keyMajor == 1) label = Math.toIntExact(-1L - keyArg);
                else return null;
                if (label == 1 || label == 3 || label == -1) {
                    int valueMajor = cursor.major();
                    long valueArg = cursor.readArg();
                    int value;
                    if (valueMajor == 0) value = Math.toIntExact(valueArg);
                    else if (valueMajor == 1) value = Math.toIntExact(-1L - valueArg);
                    else return null;
                    if (label == 1) kty = value;
                    else if (label == 3) alg = value;
                    else crv = value;
                } else if (label == -2 || label == -3) {
                    if (cursor.major() != 2) return null;
                    byte[] raw = cursor.readBytes();
                    if (label == -2) x = raw;
                    else y = raw;
                } else {
                    cursor.skip();
                }
            }
            if (kty == null || kty != 2 || (alg != null && alg != -7) || crv == null || crv != 1) return null;
            if (x == null || y == null || x.length != 32 || y.length != 32) return null;
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec ecParams = params.getParameterSpec(ECParameterSpec.class);
            ECPoint point = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, ecParams));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean rpIdHashMatches(byte[] actualHash, String expectedRpId) throws GeneralSecurityException {
        if (actualHash == null || expectedRpId == null || expectedRpId.isBlank()) return false;
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(expectedRpId.getBytes(StandardCharsets.UTF_8));
        if (MessageDigest.isEqual(expected, actualHash)) return true;
        if ("localhost".equalsIgnoreCase(expectedRpId)) {
            byte[] ipHash = MessageDigest.getInstance("SHA-256").digest("127.0.0.1".getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(ipHash, actualHash);
        }
        if ("127.0.0.1".equals(expectedRpId)) {
            byte[] nameHash = MessageDigest.getInstance("SHA-256").digest("localhost".getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(nameHash, actualHash);
        }
        return false;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String ip = ClientIpResolver.resolve(request);
        if (ip == null || ip.isBlank() || "UNKNOWN".equals(ip)) return "未知 IP";
        return ip;
    }

    private String parseSimpleUserAgent(String ua) {
        if (ua == null || ua.isBlank()) return "未知设备";
        String os = "其他系统";
        if (ua.contains("Mac OS X") || ua.contains("Macintosh")) os = "macOS";
        else if (ua.contains("Windows")) os = "Windows";
        else if (ua.contains("iPhone")) os = "iOS (iPhone)";
        else if (ua.contains("iPad")) os = "iPadOS";
        else if (ua.contains("Android")) os = "Android";
        else if (ua.contains("Linux")) os = "Linux";

        String browser = "浏览器";
        if (ua.contains("Edg/")) browser = "Edge";
        else if (ua.contains("Chrome/") && !ua.contains("Edg/")) browser = "Chrome";
        else if (ua.contains("Safari/") && !ua.contains("Chrome")) browser = "Safari";
        else if (ua.contains("Firefox/")) browser = "Firefox";

        return os + " · " + browser;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 🛡️ 严格校验 WebAuthn ClientData Origin 与预期 Host / 域名的从属关系，阻断跨站中继与钓鱼
     */
    private boolean verifyOrigin(String clientOrigin, String expectedRpId, HttpServletRequest request) {
        if (clientOrigin == null || clientOrigin.isBlank()) return false;
        if (expectedRpId == null || expectedRpId.isBlank()) return false;
        try {
            URI uri = URI.create(clientOrigin);
            if (uri.getUserInfo() != null) return false;
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT).trim();

            boolean localRp = "localhost".equalsIgnoreCase(expectedRpId) || "127.0.0.1".equals(expectedRpId);
            if (localRp) {
                if (!"localhost".equals(host) && !"127.0.0.1".equals(host)) return false;
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
            } else {
                if (!"https".equalsIgnoreCase(scheme)) return false;
                if (!host.equalsIgnoreCase(expectedRpId)) return false;
            }

            if (request != null) {
                String requestOrigin = request.getHeader("Origin");
                if (requestOrigin != null && !requestOrigin.isBlank()) {
                    URI reqUri = URI.create(requestOrigin);
                    if (reqUri.getHost() == null || !reqUri.getHost().equalsIgnoreCase(host)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 定长 CBOR 读取，拒绝不定长编码。只覆盖 attestationObject 和 COSE key 需要的类型。 */
    private static final class CborCursor {
        private final byte[] data;
        private int pos;

        private CborCursor(byte[] data, int pos) {
            this.data = data;
            this.pos = pos;
        }

        private int major() {
            if (data == null || pos >= data.length) throw new IllegalArgumentException("cbor eof");
            return (data[pos] >> 5) & 7;
        }

        private long readArg() {
            int additional = data[pos] & 0x1f;
            pos++;
            if (additional < 24) return additional;
            if (additional == 24) {
                need(1);
                return data[pos++] & 0xffL;
            }
            if (additional == 25) {
                need(2);
                long value = ((data[pos] & 0xffL) << 8) | (data[pos + 1] & 0xffL);
                pos += 2;
                return value;
            }
            if (additional == 26) {
                need(4);
                long value = ((data[pos] & 0xffL) << 24)
                        | ((data[pos + 1] & 0xffL) << 16)
                        | ((data[pos + 2] & 0xffL) << 8)
                        | (data[pos + 3] & 0xffL);
                pos += 4;
                return value;
            }
            throw new IllegalArgumentException("unsupported cbor length");
        }

        private byte[] readBytes() {
            if (major() != 2) throw new IllegalArgumentException("expected bytes");
            int length = Math.toIntExact(readArg());
            need(length);
            byte[] out = Arrays.copyOfRange(data, pos, pos + length);
            pos += length;
            return out;
        }

        private String readText() {
            if (major() != 3) throw new IllegalArgumentException("expected text");
            int length = Math.toIntExact(readArg());
            need(length);
            String text = new String(data, pos, length, StandardCharsets.UTF_8);
            pos += length;
            return text;
        }

        private void skip() {
            int type = major();
            int additional = data[pos] & 0x1f;
            if (type == 7) {
                if (additional < 24) {
                    pos++;
                    return;
                }
                if (additional == 24) {
                    pos += 2;
                    return;
                }
                if (additional == 25) {
                    pos += 3;
                    return;
                }
                if (additional == 26) {
                    pos += 5;
                    return;
                }
                if (additional == 27) {
                    pos += 9;
                    return;
                }
                throw new IllegalArgumentException("bad cbor simple");
            }
            long count = readArg();
            if (type == 0 || type == 1) return;
            if (type == 2 || type == 3) {
                int length = Math.toIntExact(count);
                need(length);
                pos += length;
                return;
            }
            if (type == 4) {
                for (long i = 0; i < count; i++) skip();
                return;
            }
            if (type == 5) {
                for (long i = 0; i < count; i++) {
                    skip();
                    skip();
                }
                return;
            }
            if (type == 6) {
                skip();
                return;
            }
            throw new IllegalArgumentException("bad cbor major");
        }

        private void need(int length) {
            if (length < 0 || pos > data.length - length) throw new IllegalArgumentException("cbor eof");
        }
    }
}
