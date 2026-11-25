package com.oci.ocimanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oci.ocimanager.entity.AdminPasskey;
import com.oci.ocimanager.entity.SystemNotification;
import com.oci.ocimanager.repository.AdminPasskeyRepository;
import com.oci.ocimanager.repository.SystemConfigRepository;
import com.oci.ocimanager.repository.SystemNotificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class AuthAndPasskeySecurityTest {

    private LocalCacheService cache;
    private AdminAuthService authService;
    private TelegramBotService telegramBotService;
    private SystemConfigRepository systemConfigRepository;

    @BeforeEach
    void setUp() {
        cache = new LocalCacheService(1024);
        telegramBotService = Mockito.mock(TelegramBotService.class);
        systemConfigRepository = Mockito.mock(SystemConfigRepository.class);

        MockEnvironment env = new MockEnvironment()
                .withProperty("app.admin-username", "admin")
                .withProperty("app.admin-password", "Sup3rS3cret!Pwd")
                .withProperty("app.admin-id", "123456789");

        authService = new AdminAuthService();
        ReflectionTestUtils.setField(authService, "redisTemplate", cache);
        ReflectionTestUtils.setField(authService, "telegramBotService", telegramBotService);
        ReflectionTestUtils.setField(authService, "systemConfigRepo", systemConfigRepository);
        ReflectionTestUtils.setField(authService, "environment", env);
        ReflectionTestUtils.setField(authService, "adminUsername", "admin");
        ReflectionTestUtils.setField(authService, "adminId", "123456789");
        authService.upgradePlaintextAdminPasswords();
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.shutdown();
        }
    }

    @Test
    void publicAuthConfigAndGlobalSendCodeCooldownAndFailCounterProtection() {
        when(telegramBotService.isBotRunning()).thenReturn(true);
        Map<String, Object> cfg = authService.getPublicAuthConfig();
        assertEquals(Boolean.TRUE, cfg.get("success"));
        assertEquals(Boolean.TRUE, cfg.get("tgBotRunning"));

        // Seed global failure counter before sendCode; sendCode must NOT clear it
        cache.opsForValue().set("oci:admin_login:global_code_fail", "4", 180, TimeUnit.SECONDS);

        Map<String, Object> first = authService.sendCode("198.51.100.1");
        assertEquals(Boolean.TRUE, first.get("success"));
        assertEquals("4", cache.opsForValue().get("oci:admin_login:global_code_fail"),
                "sendCode must not reset GLOBAL_CODE_FAIL_KEY");

        // Second request from a different IP within 60s must hit global send-code cooldown
        Map<String, Object> second = authService.sendCode("203.0.113.2");
        assertEquals(Boolean.FALSE, second.get("success"));
        assertTrue(String.valueOf(second.get("error")).contains("频繁"));
    }

    @Test
    void stepUpGuardRequiresVerificationAndCachesFiveMinuteWindow() {
        Map<String, Object> loginRes = authService.login("admin", "Sup3rS3cret!Pwd", "127.0.0.1");
        assertEquals(Boolean.TRUE, loginRes.get("success"));
        String token = String.valueOf(loginRes.get("token"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Admin-Token", token);

        // Fresh login has no step-up yet -> high-risk endpoint must demand stepUpRequired=true
        Map<String, Object> withoutStepUp = authService.verifyOrRequireStepUp(req, Map.of());
        assertEquals(Boolean.FALSE, withoutStepUp.get("success"));
        assertEquals(Boolean.TRUE, withoutStepUp.get("stepUpRequired"));

        // Wrong stepUpSecret rejected
        Map<String, Object> wrongStepUp = authService.verifyOrRequireStepUp(req, Map.of("stepUpSecret", "wrong-pass"));
        assertEquals(Boolean.FALSE, wrongStepUp.get("success"));
        assertEquals(Boolean.TRUE, wrongStepUp.get("stepUpRequired"));

        // Valid stepUpSecret succeeds and grants step-up window for subsequent calls
        Map<String, Object> okStepUp = authService.verifyOrRequireStepUp(req, Map.of("stepUpSecret", "Sup3rS3cret!Pwd"));
        assertEquals(Boolean.TRUE, okStepUp.get("success"));

        Map<String, Object> cachedStepUp = authService.verifyOrRequireStepUp(req, Map.of());
        assertEquals(Boolean.TRUE, cachedStepUp.get("success"));
    }

    @Test
    void passkeyServiceSupportsConcurrentChallengeIdsAndBothEs256AndRs256WithSignCountProtection() throws Exception {
        List<AdminPasskey> inMemoryDb = new ArrayList<>();
        AtomicLong idGen = new AtomicLong(1L);
        AdminPasskeyRepository passkeyRepo = Mockito.mock(AdminPasskeyRepository.class);
        when(passkeyRepo.findAll()).thenAnswer(inv -> new ArrayList<>(inMemoryDb));
        when(passkeyRepo.count()).thenAnswer(inv -> (long) inMemoryDb.size());
        when(passkeyRepo.findByCredentialId(any())).thenAnswer(inv -> {
            String cid = inv.getArgument(0);
            return inMemoryDb.stream().filter(p -> cid.equals(p.getCredentialId())).findFirst();
        });
        when(passkeyRepo.save(any(AdminPasskey.class))).thenAnswer(inv -> {
            AdminPasskey p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(idGen.getAndIncrement());
                inMemoryDb.add(p);
            }
            return p;
        });

        AdminPasskeyService passkeyService = new AdminPasskeyService();
        ReflectionTestUtils.setField(passkeyService, "adminPasskeyRepository", passkeyRepo);
        ReflectionTestUtils.setField(passkeyService, "adminAuthService", authService);
        ReflectionTestUtils.setField(passkeyService, "telegramBotService", telegramBotService);
        ReflectionTestUtils.setField(passkeyService, "redisTemplate", cache);
        ReflectionTestUtils.setField(passkeyService, "objectMapper", new ObjectMapper());

        String sessionToken = authService.issueSession();
        String rpId = "panel.example.com";
        String origin = "https://panel.example.com";

        MockHttpServletRequest httpsReq = new MockHttpServletRequest();
        httpsReq.setRemoteAddr("198.51.100.10");
        httpsReq.addHeader("Origin", origin);

        // Concurrent register options in two tabs must receive distinct challengeIds without clobbering
        Map<String, Object> optEs = passkeyService.generateRegisterOptions(
                sessionToken, rpId, "admin", "Sup3rS3cret!Pwd", httpsReq);
        Map<String, Object> optRs = passkeyService.generateRegisterOptions(
                sessionToken, rpId, "admin", "Sup3rS3cret!Pwd", httpsReq);
        assertEquals(Boolean.TRUE, optEs.get("success"));
        assertEquals(Boolean.TRUE, optRs.get("success"));
        assertNotEquals(optEs.get("challengeId"), optRs.get("challengeId"));

        // 1) Register ES256 (-7) key using first tab's challengeId (proving tab 2 did NOT overwrite tab 1!)
        KeyPairGenerator ecGen = KeyPairGenerator.getInstance("EC");
        ecGen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ecPair = ecGen.generateKeyPair();
        byte[] ecCredId = "cred-es256-0001".getBytes(StandardCharsets.UTF_8);
        String ecCredIdB64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(ecCredId);

        byte[] ecCose = encodeEs256CosePublicKey((ECPublicKey) ecPair.getPublic());
        byte[] ecAttObj = encodeAttestationObject(rpId, 10L, ecCredId, ecCose);
        String ecRegClientData = encodeClientData("webauthn.create", String.valueOf(optEs.get("challenge")), origin);

        Map<String, Object> ecRegBody = new HashMap<>();
        ecRegBody.put("challengeId", optEs.get("challengeId"));
        ecRegBody.put("credentialId", ecCredIdB64Url);
        ecRegBody.put("clientDataJSON", ecRegClientData);
        ecRegBody.put("attestationObject", Base64.getUrlEncoder().withoutPadding().encodeToString(ecAttObj));
        ecRegBody.put("publicKeySpki", Base64.getEncoder().encodeToString(ecPair.getPublic().getEncoded()));
        ecRegBody.put("algorithm", -7);
        ecRegBody.put("name", "YubiKey-ES256");

        Map<String, Object> ecRegRes = passkeyService.verifyAndSaveRegistration(sessionToken, ecRegBody, httpsReq);
        assertEquals(Boolean.TRUE, ecRegRes.get("success"), () -> "ES256 registration failed: " + ecRegRes);
        Optional<AdminPasskey> savedEs = inMemoryDb.stream().filter(p -> ecCredIdB64Url.equals(p.getCredentialId())).findFirst();
        assertTrue(savedEs.isPresent());
        assertEquals(10L, savedEs.get().getSignCount(), "Initial hardware signCount must be preserved from authData");

        // Re-mark step-up and verify RS256 (-257) registration using second tab's challengeId
        authService.markStepUp(sessionToken);
        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(2048);
        KeyPair rsaPair = rsaGen.generateKeyPair();
        byte[] rsaCredId = "cred-rs256-0002".getBytes(StandardCharsets.UTF_8);
        String rsaCredIdB64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(rsaCredId);

        byte[] rsaCose = encodeRs256CosePublicKey((RSAPublicKey) rsaPair.getPublic());
        byte[] rsaAttObj = encodeAttestationObject(rpId, 5L, rsaCredId, rsaCose);
        String rsaRegClientData = encodeClientData("webauthn.create", String.valueOf(optRs.get("challenge")), origin);

        Map<String, Object> rsaRegBody = new HashMap<>();
        rsaRegBody.put("challengeId", optRs.get("challengeId"));
        rsaRegBody.put("credentialId", rsaCredIdB64Url);
        rsaRegBody.put("clientDataJSON", rsaRegClientData);
        rsaRegBody.put("attestationObject", Base64.getUrlEncoder().withoutPadding().encodeToString(rsaAttObj));
        rsaRegBody.put("publicKeySpki", Base64.getEncoder().encodeToString(rsaPair.getPublic().getEncoded()));
        rsaRegBody.put("algorithm", -257);
        rsaRegBody.put("name", "WinHello-RS256");

        Map<String, Object> rsaRegRes = passkeyService.verifyAndSaveRegistration(sessionToken, rsaRegBody, httpsReq);
        assertEquals(Boolean.TRUE, rsaRegRes.get("success"), () -> "RS256 registration failed: " + rsaRegRes);

        // 2) Verify RS256 assertion login end-to-end
        Map<String, Object> loginOpt1 = passkeyService.generateLoginOptions(rpId, httpsReq);
        Map<String, Object> rsaLoginBody = buildSignedAssertion(
                String.valueOf(loginOpt1.get("challengeId")),
                String.valueOf(loginOpt1.get("challenge")),
                rpId,
                origin,
                rsaCredIdB64Url,
                6L,
                rsaPair,
                "SHA256withRSA"
        );
        Map<String, Object> rsaLoginRes = passkeyService.verifyLogin(rsaLoginBody, httpsReq);
        assertEquals(Boolean.TRUE, rsaLoginRes.get("success"), () -> "RS256 login failed: " + rsaLoginRes);
        assertNotNull(rsaLoginRes.get("token"));

        // 3) Cloned authenticator with non-incrementing signCount (<= 6) OR downgraded zero signCount (0) must be blocked!
        Map<String, Object> loginOptReplay = passkeyService.generateLoginOptions(rpId, httpsReq);
        Map<String, Object> replayBody = buildSignedAssertion(
                String.valueOf(loginOptReplay.get("challengeId")),
                String.valueOf(loginOptReplay.get("challenge")),
                rpId,
                origin,
                rsaCredIdB64Url,
                6L,
                rsaPair,
                "SHA256withRSA"
        );
        Map<String, Object> replayRes = passkeyService.verifyLogin(replayBody, httpsReq);
        assertEquals(Boolean.FALSE, replayRes.get("success"), "Cloned non-incrementing signCount must be rejected");

        Map<String, Object> loginOptZeroDowngrade = passkeyService.generateLoginOptions(rpId, httpsReq);
        Map<String, Object> zeroDowngradeBody = buildSignedAssertion(
                String.valueOf(loginOptZeroDowngrade.get("challengeId")),
                String.valueOf(loginOptZeroDowngrade.get("challenge")),
                rpId,
                origin,
                rsaCredIdB64Url,
                0L,
                rsaPair,
                "SHA256withRSA"
        );
        Map<String, Object> zeroDowngradeRes = passkeyService.verifyLogin(zeroDowngradeBody, httpsReq);
        assertEquals(Boolean.FALSE, zeroDowngradeRes.get("success"),
                "Hardware authenticator with non-zero signCount must reject signCount=0 downgrade attack");

        // 4) Passkey Step-Up verification marks session step-up window without issuing a new session token
        String stepUpSession = authService.issueSession();
        assertFalse(authService.hasStepUp(stepUpSession));
        Map<String, Object> stepUpOpt = passkeyService.generateLoginOptions(rpId, httpsReq);
        Map<String, Object> stepUpAssertion = buildSignedAssertion(
                String.valueOf(stepUpOpt.get("challengeId")),
                String.valueOf(stepUpOpt.get("challenge")),
                rpId,
                origin,
                rsaCredIdB64Url,
                7L,
                rsaPair,
                "SHA256withRSA"
        );
        Map<String, Object> stepUpVerifyRes = passkeyService.verifyStepUp(stepUpSession, stepUpAssertion, httpsReq);
        assertEquals(Boolean.TRUE, stepUpVerifyRes.get("success"), () -> "Passkey Step-Up failed: " + stepUpVerifyRes);
        assertNull(stepUpVerifyRes.get("token"), "Step-Up must not mint a new session token");
        assertTrue(authService.hasStepUp(stepUpSession), "Passkey Step-Up must activate step-up window on existing session");
    }

    @Test
    void highRiskControllersExecuteRealOperationsAfterStepUpIncludingOneTimeTelegramDynamicCodeReplay() throws Exception {
        when(telegramBotService.isBotRunning()).thenReturn(true);

        // Login admin session
        Map<String, Object> loginRes = authService.login("admin", "Sup3rS3cret!Pwd", "127.0.0.1");
        String token = String.valueOf(loginRes.get("token"));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Admin-Token", token);

        // Request a 6-digit Telegram dynamic code and capture it from cache
        Map<String, Object> sendRes = authService.sendCode("127.0.0.1");
        assertEquals(Boolean.TRUE, sendRes.get("success"));
        String dynamicCode = cache.opsForValue().get("oci:admin_login:code:127.0.0.1");
        assertNotNull(dynamicCode);
        assertEquals(6, dynamicCode.length());

        // Wire AdminAuthController + AdminInstanceController + AdminAccountController
        com.oci.ocimanager.controller.admin.AdminAuthController authController =
                new com.oci.ocimanager.controller.admin.AdminAuthController();
        ReflectionTestUtils.setField(authController, "adminAuthService", authService);

        OciCloudService cloudService = Mockito.mock(OciCloudService.class);
        Map<String, Map<String, String>> instMap = new HashMap<>();
        instMap.put("ocid1.instance.oc1.ap-tokyo-1.del1", new HashMap<>(Map.of(
                "account", "[acc-del]", "name", "[acc-del] web-node", "state", "RUNNING")));
        instMap.put("ocid1.instance.oc1.ap-tokyo-1.reinst1", new HashMap<>(Map.of(
                "account", "[acc-del]", "name", "[acc-del] db-node", "state", "RUNNING")));
        when(cloudService.syncAllInstances()).thenReturn(instMap);
        when(cloudService.deleteInstance("ocid1.instance.oc1.ap-tokyo-1.del1", "[acc-del]")).thenReturn(true);
        when(cloudService.reinstallInstance(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        com.oci.ocimanager.controller.admin.AdminInstanceController instController =
                new com.oci.ocimanager.controller.admin.AdminInstanceController();
        ReflectionTestUtils.setField(instController, "ociCloudService", cloudService);
        ReflectionTestUtils.setField(instController, "adminAuthService", authService);

        // 1) Without step-up, deleteInstance must return stepUpRequired=true and NEVER call cloudService.deleteInstance
        Map<String, Object> preStepUp = instController.deleteInstance(req, Map.of("ocid", "ocid1.instance.oc1.ap-tokyo-1.del1"));
        assertEquals(Boolean.FALSE, preStepUp.get("success"));
        assertEquals(Boolean.TRUE, preStepUp.get("stepUpRequired"));
        verify(cloudService, never()).deleteInstance(any(), any());

        // 2) Verify Step-Up using the one-time 6-digit Telegram dynamic code (which consumes & deletes the code!)
        Map<String, Object> stepUpRes = authController.stepUp(Map.of("stepUpSecret", dynamicCode), req);
        assertEquals(Boolean.TRUE, stepUpRes.get("success"));
        assertNull(cache.opsForValue().get("oci:admin_login:code:127.0.0.1"), "One-time dynamic code should be consumed");

        // 3) Replay deleteInstance (even if stepUpSecret were still present or omitted) -> MUST succeed AND actually call deleteInstance!
        Map<String, Object> delRes = instController.deleteInstance(
                req, Map.of("ocid", "ocid1.instance.oc1.ap-tokyo-1.del1", "stepUpSecret", dynamicCode));
        assertEquals(Boolean.TRUE, delRes.get("success"), () -> "deleteInstance replay failed: " + delRes);
        verify(cloudService, times(1)).deleteInstance("ocid1.instance.oc1.ap-tokyo-1.del1", "[acc-del]");
        assertFalse(instMap.containsKey("ocid1.instance.oc1.ap-tokyo-1.del1"),
                "Deleted instance must actually be removed from instance map (not short-circuited)");

        // 4) Reinstall instance within active step-up window -> MUST actually invoke cloudService.reinstallInstance and return generatedPwd!
        Map<String, Object> reinstPayload = new HashMap<>();
        reinstPayload.put("ocid", "ocid1.instance.oc1.ap-tokyo-1.reinst1");
        reinstPayload.put("osName", "Canonical Ubuntu");
        reinstPayload.put("osVersion", "24.04");
        reinstPayload.put("authType", "password");
        reinstPayload.put("rootLogin", true);
        reinstPayload.put("rootPwd", "Reinstall#Pass123");
        Map<String, Object> reinstRes = instController.reinstallInstance(req, reinstPayload);
        assertEquals(Boolean.TRUE, reinstRes.get("success"), () -> "reinstallInstance failed: " + reinstRes);
        assertEquals("Reinstall#Pass123", reinstRes.get("generatedPwd"),
                "reinstallInstance must execute full body and return generatedPwd");
        verify(cloudService, times(1)).reinstallInstance(
                eq("ocid1.instance.oc1.ap-tokyo-1.reinst1"), eq("[acc-del]"), any(),
                eq("Canonical Ubuntu"), eq("24.04"), any(), any(), eq("Reinstall#Pass123"));
        instController.destroy();

        // 5) AdminAccountController.deleteAccount must reject nonexistent account without touching YAML, and actually delete existing account!
        java.nio.file.Path tmpYml = java.nio.file.Files.createTempFile("ocibox-acc-del-", ".yml");
        try {
            String initialYaml = "oci:\n  accounts:\n    \"[acc-del]\":\n      user: \"ocid1.user.1\"\n      tenancy: \"ocid1.tenancy.1\"\n      region: \"ap-tokyo-1\"\n      fingerprint: \"aa:bb\"\n      key-file: \"/nonexistent.pem\"\n";
            java.nio.file.Files.writeString(tmpYml, initialYaml, StandardCharsets.UTF_8);

            AccountYamlService yamlService = new AccountYamlService() {
                @Override
                public java.io.File getConfigFile() {
                    return tmpYml.toFile();
                }
            };
            com.oci.ocimanager.config.OciProperties props = new com.oci.ocimanager.config.OciProperties();
            Map<String, com.oci.ocimanager.config.OciProperties.Account> accounts = new java.util.LinkedHashMap<>();
            accounts.put("[acc-del]", new com.oci.ocimanager.config.OciProperties.Account());
            props.setAccounts(accounts);

            com.oci.ocimanager.controller.admin.AdminAccountController accController =
                    new com.oci.ocimanager.controller.admin.AdminAccountController();
            ReflectionTestUtils.setField(accController, "adminAuthService", authService);
            ReflectionTestUtils.setField(accController, "accountYamlService", yamlService);
            ReflectionTestUtils.setField(accController, "ociProperties", props);
            ReflectionTestUtils.setField(accController, "ociCloudService", cloudService);

            Map<String, Object> missingAccRes = accController.deleteAccount(req, Map.of("account", "does-not-exist"));
            assertEquals(Boolean.FALSE, missingAccRes.get("success"));
            assertEquals(initialYaml, java.nio.file.Files.readString(tmpYml, StandardCharsets.UTF_8),
                    "Deleting nonexistent account must not touch application.yml");

            Map<String, Object> realDelAccRes = accController.deleteAccount(req, Map.of("account", "acc-del"));
            assertEquals(Boolean.TRUE, realDelAccRes.get("success"));
            assertTrue(props.getAccounts().isEmpty(), "Account must actually be deleted from memory (not short-circuited)");
            assertFalse(java.nio.file.Files.readString(tmpYml, StandardCharsets.UTF_8).contains("acc-del"),
                    "Account must actually be deleted from application.yml");
        } finally {
            java.nio.file.Files.deleteIfExists(tmpYml);
        }
    }

    @Test
    void notificationServiceRedactsTgSpoilerBeforeSavingToSqlite() {
        SystemNotificationRepository repo = Mockito.mock(SystemNotificationRepository.class);
        when(repo.save(any(SystemNotification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.count()).thenReturn(1L);
        when(telegramBotService.isBotRunning()).thenReturn(true);

        NotificationService notificationService = new NotificationService();
        ReflectionTestUtils.setField(notificationService, "notificationRepo", repo);
        ReflectionTestUtils.setField(notificationService, "telegramBotService", telegramBotService);
        ReflectionTestUtils.setField(notificationService, "adminId", "123456789");

        String rawHtml = "实例重装完成\nRoot 密码: <tg-spoiler>SuperSecretRootPwd!99</tg-spoiler>";
        SystemNotification saved = notificationService.send("实例重装通知", rawHtml, "SUCCESS", "INSTANCE", true);

        assertFalse(saved.getContent().contains("SuperSecretRootPwd!99"),
                "SQLite persisted notification must not contain plaintext password");
        assertTrue(saved.getContent().contains("已脱敏保护"));
        verify(telegramBotService).sendHtmlText(anyLong(), contains("<tg-spoiler>SuperSecretRootPwd!99</tg-spoiler>"));
    }

    private static String encodeClientData(String type, String challenge, String origin) {
        String json = "{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin + "\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> buildSignedAssertion(
            String challengeId,
            String challenge,
            String rpId,
            String origin,
            String credIdB64Url,
            long signCount,
            KeyPair keyPair,
            String sigAlg) throws Exception {
        String clientDataB64 = encodeClientData("webauthn.get", challenge, origin);
        byte[] clientDataBytes = Base64.getUrlDecoder().decode(clientDataB64);
        byte[] clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataBytes);

        byte[] rpHash = MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8));
        ByteBuffer authBuf = ByteBuffer.allocate(37);
        authBuf.put(rpHash);
        authBuf.put((byte) 0x05); // UP | UV
        authBuf.putInt((int) signCount);
        byte[] authData = authBuf.array();

        byte[] toSign = new byte[authData.length + clientDataHash.length];
        System.arraycopy(authData, 0, toSign, 0, authData.length);
        System.arraycopy(clientDataHash, 0, toSign, authData.length, clientDataHash.length);

        Signature sig = Signature.getInstance(sigAlg);
        sig.initSign(keyPair.getPrivate());
        sig.update(toSign);
        byte[] sigBytes = sig.sign();

        Map<String, Object> body = new HashMap<>();
        body.put("challengeId", challengeId);
        body.put("credentialId", credIdB64Url);
        body.put("clientDataJSON", clientDataB64);
        body.put("authenticatorData", Base64.getUrlEncoder().withoutPadding().encodeToString(authData));
        body.put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes));
        return body;
    }

    private static byte[] encodeAttestationObject(String rpId, long signCount, byte[] credId, byte[] coseKey) throws Exception {
        byte[] rpHash = MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream authOut = new ByteArrayOutputStream();
        authOut.write(rpHash);
        authOut.write(0x45); // UP (0x01) | UV (0x04) | AT (0x40)
        authOut.write(ByteBuffer.allocate(4).putInt((int) signCount).array());
        authOut.write(new byte[16]); // AAGUID
        authOut.write(ByteBuffer.allocate(2).putShort((short) credId.length).array());
        authOut.write(credId);
        authOut.write(coseKey);
        byte[] authData = authOut.toByteArray();

        ByteArrayOutputStream cbor = new ByteArrayOutputStream();
        cbor.write(0xa2); // map(2)
        writeCborText(cbor, "fmt");
        writeCborText(cbor, "none");
        writeCborText(cbor, "authData");
        writeCborBytes(cbor, authData);
        return cbor.toByteArray();
    }

    private static byte[] encodeEs256CosePublicKey(ECPublicKey pub) {
        byte[] x = toFixedUnsigned(pub.getW().getAffineX(), 32);
        byte[] y = toFixedUnsigned(pub.getW().getAffineY(), 32);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xa5); // map(5)
        out.write(0x01); out.write(0x02); // 1: 2 (EC2)
        out.write(0x03); out.write(0x26); // 3: -7 (ES256)
        out.write(0x20); out.write(0x01); // -1: 1 (P-256)
        out.write(0x21); writeCborBytes(out, x); // -2: x
        out.write(0x22); writeCborBytes(out, y); // -3: y
        return out.toByteArray();
    }

    private static byte[] encodeRs256CosePublicKey(RSAPublicKey pub) {
        byte[] n = stripLeadingSignZero(pub.getModulus());
        byte[] e = stripLeadingSignZero(pub.getPublicExponent());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xa4); // map(4)
        out.write(0x01); out.write(0x03); // 1: 3 (RSA)
        out.write(0x03); out.write(0x39); out.write(0x01); out.write(0x00); // 3: -257 (RS256)
        out.write(0x20); writeCborBytes(out, n); // -1: modulus
        out.write(0x21); writeCborBytes(out, e); // -2: exponent
        return out.toByteArray();
    }

    private static void writeCborText(ByteArrayOutputStream out, String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        writeCborHeader(out, 3, utf8.length);
        out.writeBytes(utf8);
    }

    private static void writeCborBytes(ByteArrayOutputStream out, byte[] data) {
        writeCborHeader(out, 2, data.length);
        out.writeBytes(data);
    }

    private static void writeCborHeader(ByteArrayOutputStream out, int major, int len) {
        int prefix = major << 5;
        if (len < 24) {
            out.write(prefix | len);
        } else if (len < 256) {
            out.write(prefix | 24);
            out.write(len);
        } else {
            out.write(prefix | 25);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        }
    }

    private static byte[] toFixedUnsigned(BigInteger val, int length) {
        byte[] raw = stripLeadingSignZero(val);
        if (raw.length == length) return raw;
        byte[] padded = new byte[length];
        System.arraycopy(raw, 0, padded, length - raw.length, raw.length);
        return padded;
    }

    private static byte[] stripLeadingSignZero(BigInteger val) {
        byte[] raw = val.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            return Arrays.copyOfRange(raw, 1, raw.length);
        }
        return raw;
    }
}
