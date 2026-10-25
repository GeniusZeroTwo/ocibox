package com.oci.ocimanager.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 管理员口令存储格式。已是哈希的值按 PBKDF2 校验，旧的明文配置仍可登录，登录或启动时再升级。
 */
public final class PasswordHasher {

    public static final String PREFIX = "pbkdf2sha256$";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static boolean isHashed(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, ITERATIONS);
        return PREFIX + ITERATIONS + "$" + encode(salt) + "$" + encode(hash);
    }

    public static boolean matches(String presented, String stored) {
        if (presented == null || stored == null) return false;
        String value = stored.trim();
        if (value.isEmpty()) return false;
        if (!isHashed(value)) {
            byte[] left = presented.getBytes(StandardCharsets.UTF_8);
            byte[] right = value.getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(left, right);
        }
        String[] parts = value.split("\\$");
        if (parts.length != 4 || !"pbkdf2sha256".equals(parts[0])) return false;
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (iterations < 10_000 || iterations > 5_000_000) return false;
        byte[] salt;
        byte[] expected;
        try {
            salt = decode(parts[2]);
            expected = decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (salt.length < 8 || expected.length < 16) return false;
        byte[] actual = pbkdf2(presented, salt, iterations);
        return MessageDigest.isEqual(actual, expected);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BYTES * 8);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 不可用", e);
        }
    }

    private static String encode(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static byte[] decode(String raw) {
        return Base64.getUrlDecoder().decode(raw);
    }
}
