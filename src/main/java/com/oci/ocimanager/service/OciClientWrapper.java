package com.oci.ocimanager.service;

import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.BlockstorageClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.limits.QuotasClient;
import com.oracle.bmc.monitoring.MonitoringClient;
import com.oracle.bmc.networkloadbalancer.NetworkLoadBalancerClient;
import com.oracle.bmc.usageapi.UsageapiClient;

import java.util.Map;

/**
 * 🌟 【内存优化】OCI 客户端懒加载包装器
 */
public class OciClientWrapper {
    private final SimpleAuthenticationDetailsProvider provider;
    private final ClientConfiguration config;

    private volatile ComputeClient compute;
    private volatile BlockstorageClient blockstorage;
    private volatile VirtualNetworkClient vnc;
    private volatile MonitoringClient monitoring;
    private volatile IdentityClient identity;
    private volatile QuotasClient quotas;
    private volatile UsageapiClient usageapi;
    private volatile NetworkLoadBalancerClient networkLoadBalancer;

    // 🚀 【性能/资源泄漏修复】按 region 复用的客户端 LRU 缓存 (最大 4 个，自动关停超出上限的客户端)。
    private static final int MAX_REGION_CLIENTS = 4;
    private final Map<String, AutoCloseable> regionClientCache = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<String, AutoCloseable>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, AutoCloseable> eldest) {
                if (size() > MAX_REGION_CLIENTS) {
                    AutoCloseable client = eldest.getValue();
                    if (client != null) {
                        try {
                            client.close();
                        } catch (Exception ignored) {}
                    }
                    return true;
                }
                return false;
            }
        }
    );

    private boolean isDefaultRegion(String regionName) {
        if (regionName == null || regionName.isBlank()) return true;
        if (provider == null || provider.getRegion() == null) return false;
        String defaultId = provider.getRegion().getRegionId();
        String defaultCode = provider.getRegion().getRegionCode();
        return regionName.equalsIgnoreCase(defaultId) || (defaultCode != null && regionName.equalsIgnoreCase(defaultCode));
    }

    @SuppressWarnings("unchecked")
    private <T> T cachedRegionClient(String type, String regionName, java.util.function.Supplier<T> builder) {
        return (T) regionClientCache.computeIfAbsent(type + ":" + regionName, k -> (AutoCloseable) builder.get());
    }

    private final String accountName;
    private final OciClientFingerprintService.ClientFingerprint fingerprint;
    private final com.oracle.bmc.http.client.ProxyConfiguration proxyConfig;

    public OciClientWrapper(String accountName, SimpleAuthenticationDetailsProvider provider, ClientConfiguration config) {
        this(accountName, provider, config, null, null);
    }

    public OciClientWrapper(String accountName, SimpleAuthenticationDetailsProvider provider, ClientConfiguration config, OciClientFingerprintService.ClientFingerprint fingerprint) {
        this(accountName, provider, config, fingerprint, null);
    }

    public OciClientWrapper(String accountName, SimpleAuthenticationDetailsProvider provider, ClientConfiguration config, OciClientFingerprintService.ClientFingerprint fingerprint, com.oci.ocimanager.config.OciProperties.Account accountConfig) {
        this.accountName = accountName;
        this.provider = provider;
        this.config = config;
        this.fingerprint = (fingerprint != null) ? fingerprint :
                new OciClientFingerprintService().getFingerprint(accountName, accountConfig);
        this.proxyConfig = (accountConfig != null && accountConfig.getProxy() != null) ?
                parseProxy(accountConfig.getProxy()) : null;
    }

    public String getAccountName() {
        return accountName;
    }

    public OciClientFingerprintService.ClientFingerprint getFingerprint() {
        return fingerprint;
    }

    public com.oracle.bmc.http.client.ProxyConfiguration getProxyConfig() {
        return proxyConfig;
    }

    private static final java.util.Set<Integer> FORBIDDEN_PROXY_PORTS = java.util.Set.of(
            22, 25, 80, 443, 3306, 5432, 6379, 9527, 11211, 27017
    );

    /**
     * 校验代理地址合法性与安全性（成功返回 null，不安全或格式非法返回错误原因）
     */
    public static String validateProxySafety(String proxyStr) {
        if (proxyStr == null || proxyStr.trim().isEmpty()) return null;
        try {
            java.net.URI uri = java.net.URI.create(proxyStr.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme)
                    && !"https".equalsIgnoreCase(scheme)
                    && !"socks".equalsIgnoreCase(scheme)
                    && !"socks5".equalsIgnoreCase(scheme))) {
                return "代理协议仅支持 http://、https://、socks:// 或 socks5://";
            }
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.isBlank() || port <= 0 || port > 65535) {
                return "代理地址必须包含合法的主机名与端口号 (1~65535)";
            }
            String lowerHost = host.trim().toLowerCase();
            if ("169.254.169.254".equals(lowerHost)
                    || lowerHost.startsWith("169.254.")
                    || "metadata.google.internal".equals(lowerHost)
                    || "0.0.0.0".equals(lowerHost)) {
                return "安全拦截：禁止将代理指向云元数据服务或保留地址";
            }
            if (FORBIDDEN_PROXY_PORTS.contains(port)
                    && ("localhost".equals(lowerHost) || lowerHost.startsWith("127.") || "::1".equals(lowerHost) || "[::1]".equals(lowerHost))) {
                return "安全拦截：禁止将本机内部敏感服务端口 (" + port + ") 配置为代理";
            }
            return null;
        } catch (Exception e) {
            return "代理地址 URI 格式不合法: " + e.getMessage();
        }
    }

    public static com.oracle.bmc.http.client.ProxyConfiguration parseProxy(String proxyStr) {
        if (proxyStr == null || proxyStr.trim().isEmpty()) return null;
        String unsafeReason = validateProxySafety(proxyStr);
        if (unsafeReason != null) {
            System.err.println("🚨 拒绝加载不安全的账号代理配置 [" + proxyStr + "]: " + unsafeReason);
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(proxyStr.trim());
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || port <= 0) return null;

            java.net.Proxy.Type type = java.net.Proxy.Type.HTTP;
            if ("socks".equalsIgnoreCase(uri.getScheme()) || "socks5".equalsIgnoreCase(uri.getScheme())) {
                type = java.net.Proxy.Type.SOCKS;
            }
            java.net.Proxy proxy = new java.net.Proxy(type, new java.net.InetSocketAddress(host, port));

            var builder = com.oracle.bmc.http.client.ProxyConfiguration.builder().proxy(proxy);
            String userInfo = uri.getUserInfo();
            if (userInfo != null && !userInfo.isEmpty()) {
                String[] parts = userInfo.split(":", 2);
                builder.username(parts[0]);
                if (parts.length > 1) {
                    builder.password(parts[1].toCharArray());
                }
            }
            return builder.build();
        } catch (Exception e) {
            System.err.println("解析账号代理配置异常 [" + proxyStr + "]: " + e.getMessage());
            return null;
        }
    }

    private <B extends com.oracle.bmc.common.ClientBuilderBase<B, ?>> B decorate(B builder) {
        builder.additionalClientConfigurator(clientBuilder -> {
            // 🌟 若账号配置了独立代理出口，绑定专用代理配置，实现各账号物理网络隔离
            if (proxyConfig != null) {
                clientBuilder.property(com.oracle.bmc.http.client.StandardClientProperties.PROXY, proxyConfig);
            }

            // 🌟 优先级设为 6000（确保在 OCI SDK 内部 ClientIdFilter 之后执行，彻底覆盖默认 User-Agent/opc-client-info 并消除双 UA 残留）
            // ⚠️ 注意：绝不能篡改 Accept-Encoding、Accept 或 opc-request-id！
            // 1. 若注入 Accept-Encoding: gzip，底层 Apache Connector 不会自动解压，导致 Jackson 解析 gzip 二进制崩溃报 (-1) Unknown error
            // 2. 篡改 opc-request-id 会破坏 OCI SDK 内部请求生命周期追踪与网关校验
            clientBuilder.registerRequestInterceptor(6000, request -> {
                if (fingerprint != null) {
                    var headers = request.headers();
                    if (headers != null) {
                        try {
                            headers.remove("User-Agent");
                            headers.remove("user-agent");
                            headers.remove("opc-client-info");
                            headers.remove("OPC-CLIENT-INFO");
                            headers.remove("Accept-Language");
                            headers.remove("accept-language");
                            headers.remove("opc-client-retries");
                            headers.remove("OPC-CLIENT-RETRIES");
                        } catch (Exception ignored) {}
                    }
                    if (fingerprint.userAgent() != null && !fingerprint.userAgent().isEmpty()) {
                        request.header("User-Agent", fingerprint.userAgent());
                    }
                    if (fingerprint.opcClientInfo() != null && !fingerprint.opcClientInfo().isEmpty()) {
                        request.header("opc-client-info", fingerprint.opcClientInfo());
                    }
                    if (fingerprint.acceptLanguage() != null && !fingerprint.acceptLanguage().isEmpty()) {
                        request.header("Accept-Language", fingerprint.acceptLanguage());
                    }
                    String style = fingerprint.requestIdStyle();
                    if ("CLI".equalsIgnoreCase(style) || "JAVA".equalsIgnoreCase(style)) {
                        request.header("opc-client-retries", "true");
                    }
                }
            });
        });
        return builder;
    }

    private static void setClientRegion(Object client, String regionName) {
        if (regionName == null || regionName.isBlank()) return;
        com.oracle.bmc.Region region = null;
        try {
            region = com.oracle.bmc.Region.fromRegionCodeOrId(regionName);
        } catch (Exception ignored) {}

        try {
            if (region != null) {
                if (client instanceof ComputeClient c) c.setRegion(region);
                else if (client instanceof BlockstorageClient c) c.setRegion(region);
                else if (client instanceof VirtualNetworkClient c) c.setRegion(region);
                else if (client instanceof MonitoringClient c) c.setRegion(region);
                else if (client instanceof IdentityClient c) c.setRegion(region);
                else if (client instanceof NetworkLoadBalancerClient c) c.setRegion(region);
                else if (client instanceof com.oracle.bmc.onesubscription.SubscriptionClient c) c.setRegion(region);
                else if (client instanceof com.oracle.bmc.ospgateway.SubscriptionServiceClient c) c.setRegion(region);
                else if (client instanceof UsageapiClient c) c.setRegion(region);
            } else {
                if (client instanceof ComputeClient c) c.setRegion(regionName);
                else if (client instanceof BlockstorageClient c) c.setRegion(regionName);
                else if (client instanceof VirtualNetworkClient c) c.setRegion(regionName);
                else if (client instanceof MonitoringClient c) c.setRegion(regionName);
                else if (client instanceof IdentityClient c) c.setRegion(regionName);
                else if (client instanceof NetworkLoadBalancerClient c) c.setRegion(regionName);
                else if (client instanceof com.oracle.bmc.onesubscription.SubscriptionClient c) c.setRegion(regionName);
                else if (client instanceof com.oracle.bmc.ospgateway.SubscriptionServiceClient c) c.setRegion(regionName);
                else if (client instanceof UsageapiClient c) c.setRegion(regionName);
            }
        } catch (Exception e) {
            System.err.println("设置客户端 Region [" + regionName + "] 失败: " + e.getMessage());
        }
    }

    public ComputeClient getCompute() {
        if (compute == null) {
            synchronized (this) {
                if (compute == null) {
                    compute = decorate(ComputeClient.builder().configuration(config)).build(provider);
                }
            }
        }
        return compute;
    }

    public ComputeClient getComputeClientForRegion(String regionName) {
        if (isDefaultRegion(regionName)) {
            return getCompute();
        }
        return cachedRegionClient("compute", regionName, () -> {
            var client = decorate(ComputeClient.builder().configuration(config)).build(provider);
            setClientRegion(client, regionName);
            return client;
        });
    }

    public BlockstorageClient getBlockstorage() {
        if (blockstorage == null) {
            synchronized (this) {
                if (blockstorage == null) {
                    blockstorage = decorate(BlockstorageClient.builder().configuration(config)).build(provider);
                }
            }
        }
        return blockstorage;
    }

    public BlockstorageClient getBlockstorageClientForRegion(String regionName) {
        if (isDefaultRegion(regionName)) {
            return getBlockstorage();
        }
        return cachedRegionClient("blockstorage", regionName, () -> {
            var client = decorate(BlockstorageClient.builder().configuration(config)).build(provider);
            setClientRegion(client, regionName);
            return client;
        });
    }

    public VirtualNetworkClient getVnc() {
        if (vnc == null) {
            synchronized (this) {
                if (vnc == null) {
                    vnc = decorate(VirtualNetworkClient.builder().configuration(config)).build(provider);
                }
            }
        }
        return vnc;
    }

    public VirtualNetworkClient getVncClientForRegion(String regionName) {
        if (isDefaultRegion(regionName)) {
            return getVnc();
        }
        return cachedRegionClient("vnc", regionName, () -> {
            var client = decorate(VirtualNetworkClient.builder().configuration(config)).build(provider);
            setClientRegion(client, regionName);
            return client;
        });
    }

    public MonitoringClient getMonitoring() {
        if (monitoring == null) {
            synchronized (this) {
                if (monitoring == null) {
                    monitoring = decorate(MonitoringClient.builder().configuration(config)).build(provider);
                }
            }
        }
        return monitoring;
    }

    public MonitoringClient getMonitoringClientForRegion(String regionName) {
        if (isDefaultRegion(regionName)) {
            return getMonitoring();
        }
        return cachedRegionClient("monitoring", regionName, () -> {
            var client = decorate(MonitoringClient.builder().configuration(config)).build(provider);
            setClientRegion(client, regionName);
            return client;
        });
    }

    public SimpleAuthenticationDetailsProvider getProvider() {
        return provider;
    }

    public IdentityClient getIdentity() {
        if (identity == null) {
            synchronized (this) {
                if (identity == null) {
                    identity = decorate(IdentityClient.builder().configuration(config)).build(provider);
                }
            }
        }
        return identity;
    }

    public IdentityClient getIdentityClientForRegion(String regionName) {
        if (isDefaultRegion(regionName)) {
            return getIdentity();
        }
        return cachedRegionClient("identity", regionName, () -> {
            var client = decorate(IdentityClient.builder().configuration(config)).build(provider);
            setClientRegion(client, regionName);
            return client;
        });
    }

    public QuotasClient getQuotas() {
        if (quotas == null) {
            synchronized (this) {
                if (quotas == null) {
                    quotas = decorate(QuotasClient.builder().configuration(config)).build(provider);
                }
            }
        }
        return quotas;
    }

    public NetworkLoadBalancerClient getNetworkLoadBalancer() {
        if (networkLoadBalancer == null) {
            synchronized (this) {
                if (networkLoadBalancer == null) {
                    networkLoadBalancer = decorate(NetworkLoadBalancerClient.builder().configuration(config)).build(provider);
                }
            }
        }
        return networkLoadBalancer;
    }

    public NetworkLoadBalancerClient getNetworkLoadBalancerClientForRegion(String regionName) {
        if (isDefaultRegion(regionName)) {
            return getNetworkLoadBalancer();
        }
        return cachedRegionClient("nlb", regionName, () -> {
            var client = decorate(NetworkLoadBalancerClient.builder().configuration(config)).build(provider);
            setClientRegion(client, regionName);
            return client;
        });
    }

    private volatile com.oracle.bmc.onesubscription.SubscriptionClient subscription;

    public com.oracle.bmc.onesubscription.SubscriptionClient getSubscription() {
        if (subscription == null) {
            synchronized (this) {
                if (subscription == null) {
                    subscription = decorate(com.oracle.bmc.onesubscription.SubscriptionClient.builder().configuration(config)).build(provider);
                }
            }
        }
        return subscription;
    }

    public com.oracle.bmc.onesubscription.SubscriptionClient getSubscriptionClientForRegion(String regionName) {
        if (isDefaultRegion(regionName)) {
            return getSubscription();
        }
        return cachedRegionClient("subscription", regionName, () -> {
            var client = decorate(com.oracle.bmc.onesubscription.SubscriptionClient.builder().configuration(config)).build(provider);
            setClientRegion(client, regionName);
            return client;
        });
    }

    public com.oracle.bmc.ospgateway.SubscriptionServiceClient getOspSubscriptionClientForRegion(String regionName) {
        return cachedRegionClient("osp_subscription", regionName, () -> {
            var client = decorate(com.oracle.bmc.ospgateway.SubscriptionServiceClient.builder().configuration(config)).build(provider);
            setClientRegion(client, regionName);
            return client;
        });
    }

    public UsageapiClient getUsageapi() {
        if (usageapi == null) {
            synchronized (this) {
                if (usageapi == null) {
                    usageapi = decorate(UsageapiClient.builder().configuration(config)).build(provider);
                }
            }
        }
        return usageapi;
    }

    public UsageapiClient getUsageapiClientForRegion(String regionName) {
        if (isDefaultRegion(regionName)) {
            return getUsageapi();
        }
        return cachedRegionClient("usageapi", regionName, () -> {
            var client = decorate(UsageapiClient.builder().configuration(config)).build(provider);
            setClientRegion(client, regionName);
            return client;
        });
    }

    /**
     * 🧹 释放低频重型客户端（如账单 UsageAPI、OSP 订阅网关、企业订阅、配额客户端），
     * 在定时账单巡检、PAYG 探测或注册时间抓取完成后即刻调用，避免在每个账号 Wrapper 中长期占据数十 MB 内存。
     */
    public synchronized void releaseLowFrequencyClients() {
        try {
            if (usageapi != null) {
                usageapi.close();
                usageapi = null;
            }
        } catch (Exception ignored) {}
        try {
            if (subscription != null) {
                subscription.close();
                subscription = null;
            }
        } catch (Exception ignored) {}
        try {
            if (quotas != null) {
                quotas.close();
                quotas = null;
            }
        } catch (Exception ignored) {}
        synchronized (regionClientCache) {
            var it = regionClientCache.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                String key = entry.getKey();
                if (key != null && (key.startsWith("osp_subscription:")
                        || key.startsWith("subscription:")
                        || key.startsWith("usageapi:"))) {
                    try {
                        if (entry.getValue() != null) {
                            entry.getValue().close();
                        }
                    } catch (Exception ignored) {}
                    it.remove();
                }
            }
        }
    }

    public void closeAll() {
        try {
            if (compute != null) {
                compute.close();
            }
        } catch (Exception e) {
            // Ignore exception on close
        }
        try {
            if (blockstorage != null) {
                blockstorage.close();
            }
        } catch (Exception e) {
            // Ignore exception on close
        }
        try {
            if (vnc != null) {
                vnc.close();
            }
        } catch (Exception e) {
            // Ignore exception on close
        }
        try {
            if (monitoring != null) {
                monitoring.close();
            }
        } catch (Exception e) {
            // Ignore exception on close
        }
        try {
            if (networkLoadBalancer != null) {
                networkLoadBalancer.close();
            }
        } catch (Exception e) {
            // Ignore exception on close
        }
        try {
            if (identity != null) {
                identity.close();
            }
        } catch (Exception e) {
            // Ignore exception on close
        }
        try {
            if (quotas != null) {
                quotas.close();
            }
        } catch (Exception e) {
            // Ignore exception on close
        }
        try {
            if (usageapi != null) {
                usageapi.close();
            }
        } catch (Exception e) {
            // Ignore exception on close
        }
        try {
            if (subscription != null) {
                subscription.close();
            }
        } catch (Exception e) {
            // Ignore exception on close
        }
        // 🚀 释放所有按 region 缓存的客户端，杜绝连接池/线程泄漏。
        synchronized (regionClientCache) {
            for (AutoCloseable c : regionClientCache.values()) {
                try {
                    if (c != null) c.close();
                } catch (Exception e) {
                    // Ignore exception on close
                }
            }
            regionClientCache.clear();
        }

        compute = null;
        blockstorage = null;
        vnc = null;
        monitoring = null;
        networkLoadBalancer = null;
        identity = null;
        quotas = null;
        usageapi = null;
        subscription = null;
    }
}
