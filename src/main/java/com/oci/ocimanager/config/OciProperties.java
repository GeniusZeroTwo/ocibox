package com.oci.ocimanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "oci")
public class OciProperties {

    private Map<String, Account> accounts = new java.util.LinkedHashMap<>();

    public Map<String, Account> getAccounts() {
        if (accounts == null) {
            accounts = new java.util.LinkedHashMap<>();
        }
        return accounts;
    }

    public void setAccounts(Map<String, Account> accounts) {
        if (accounts != null && !(accounts instanceof java.util.LinkedHashMap)) {
            this.accounts = new java.util.LinkedHashMap<>(accounts);
        } else {
            this.accounts = accounts != null ? accounts : new java.util.LinkedHashMap<>();
        }
    }

    public static class Account {
        private String user;
        private String fingerprint;
        private String tenancy;
        private String region;
        private String keyFile;
        // 🌟 新增：账户注册时间字段
        private String registerTime;
        // 🌟 新增：安全邮箱字段（支持密码重置接收）
        private String email;

        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }

        public String getFingerprint() { return fingerprint; }
        public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

        public String getTenancy() { return tenancy; }
        public void setTenancy(String tenancy) { this.tenancy = tenancy; }

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }

        public String getKeyFile() { return keyFile; }
        public void setKeyFile(String keyFile) { this.keyFile = keyFile; }

        public String getRegisterTime() { return registerTime; }
        public void setRegisterTime(String registerTime) { this.registerTime = registerTime; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        // 🌟 新增：独立客户端标识与 User-Agent（用于多账号请求特征差异化，可自定义）
        private String clientIdentifier;

        public String getClientIdentifier() { return clientIdentifier; }
        public void setClientIdentifier(String clientIdentifier) { this.clientIdentifier = clientIdentifier; }

        // 🌟 新增：各账号独立网络出口代理 (支持 http:// 或 socks5://)
        private String proxy;

        public String getProxy() { return proxy; }
        public void setProxy(String proxy) { this.proxy = proxy; }
    }
}
