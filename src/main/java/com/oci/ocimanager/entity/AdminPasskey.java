package com.oci.ocimanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 🔐 管理员 WebAuthn 通行密钥 (Passkey) 凭证实体
 */
@Entity
@Table(name = "admin_passkey")
public class AdminPasskey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * WebAuthn 凭证全局唯一 ID (Base64URL 编码)
     */
    @Column(name = "credential_id", nullable = false, unique = true, columnDefinition = "TEXT")
    private String credentialId;

    /**
     * 公钥 SPKI (SubjectPublicKeyInfo) DER 编码的 Base64 字符串
     */
    @Column(name = "public_key_spki", nullable = false, columnDefinition = "TEXT")
    private String publicKeySpki;

    /**
     * 算法标识符：-7 表示 ES256 (ECDSA P-256)，-257 表示 RS256
     */
    @Column(name = "algorithm", nullable = false)
    private Integer algorithm = -7;

    /**
     * 密钥自定义备注名称，如 "MacBook Touch ID", "iPhone FaceID", "YubiKey 5C"
     */
    @Column(name = "name", nullable = false)
    private String name;

    /**
     * 签名计数器 (防重放校验)
     */
    @Column(name = "sign_count")
    private Long signCount = 0L;

    /**
     * 认证器 AAGUID (可选)
     */
    @Column(name = "aaguid")
    private String aaguid;

    /**
     * 创建时间
     */
    @Column(name = "created_at")
    private String createdAt;

    /**
     * 最后使用时间
     */
    @Column(name = "last_used_at")
    private String lastUsedAt;

    public AdminPasskey() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(String credentialId) {
        this.credentialId = credentialId;
    }

    public String getPublicKeySpki() {
        return publicKeySpki;
    }

    public void setPublicKeySpki(String publicKeySpki) {
        this.publicKeySpki = publicKeySpki;
    }

    public Integer getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(Integer algorithm) {
        this.algorithm = algorithm;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getSignCount() {
        return signCount;
    }

    public void setSignCount(Long signCount) {
        this.signCount = signCount;
    }

    public String getAaguid() {
        return aaguid;
    }

    public void setAaguid(String aaguid) {
        this.aaguid = aaguid;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(String lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
