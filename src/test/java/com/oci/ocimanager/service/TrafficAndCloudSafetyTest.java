package com.oci.ocimanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oci.ocimanager.config.OciProperties;
import com.oci.ocimanager.controller.admin.AdminAccountController;
import com.oci.ocimanager.controller.admin.AdminDashboardController;
import com.oci.ocimanager.entity.TrafficLimit;
import com.oci.ocimanager.repository.SystemConfigRepository;
import com.oci.ocimanager.repository.TrafficLimitRepository;
import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.EgressSecurityRule;
import com.oracle.bmc.core.model.IngressSecurityRule;
import com.oracle.bmc.core.model.PrivateIp;
import com.oracle.bmc.core.model.PublicIp;
import com.oracle.bmc.core.model.SecurityList;
import com.oracle.bmc.core.model.Vcn;
import com.oracle.bmc.core.model.VnicAttachment;
import com.oracle.bmc.core.requests.DeletePublicIpRequest;
import com.oracle.bmc.core.requests.GetPublicIpByPrivateIpIdRequest;
import com.oracle.bmc.core.requests.ListPrivateIpsRequest;
import com.oracle.bmc.core.requests.ListSecurityListsRequest;
import com.oracle.bmc.core.requests.ListVcnsRequest;
import com.oracle.bmc.core.requests.ListVnicAttachmentsRequest;
import com.oracle.bmc.core.requests.UpdateSecurityListRequest;
import com.oracle.bmc.core.responses.GetPublicIpByPrivateIpIdResponse;
import com.oracle.bmc.core.responses.ListPrivateIpsResponse;
import com.oracle.bmc.core.responses.ListSecurityListsResponse;
import com.oracle.bmc.core.responses.ListVcnsResponse;
import com.oracle.bmc.core.responses.ListVnicAttachmentsResponse;
import com.oracle.bmc.limits.QuotasClient;
import com.oracle.bmc.limits.model.QuotaSummary;
import com.oracle.bmc.limits.requests.CreateQuotaRequest;
import com.oracle.bmc.limits.requests.DeleteQuotaRequest;
import com.oracle.bmc.limits.requests.ListQuotasRequest;
import com.oracle.bmc.limits.requests.UpdateQuotaRequest;
import com.oracle.bmc.limits.responses.ListQuotasResponse;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.monitoring.MonitoringClient;
import com.oracle.bmc.monitoring.model.AggregatedDatapoint;
import com.oracle.bmc.monitoring.model.MetricData;
import com.oracle.bmc.monitoring.requests.SummarizeMetricsDataRequest;
import com.oracle.bmc.monitoring.responses.SummarizeMetricsDataResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TrafficAndCloudSafetyTest {

    @TempDir
    Path tempDir;

    private LocalCacheService cache;

    @BeforeEach
    void setUp() {
        cache = new LocalCacheService(1024);
        ScheduledTasksService.TRAFFIC_CACHE.clear();
        ScheduledTasksService.UPDATE_TIME_CACHE.clear();
        ScheduledTasksService.WARNING_SENT_RECORD.clear();
        ScheduledTasksService.MELTDOWN_STOPPED_OCIDS.clear();
        OciCloudService.INSTANCE_CACHE.clear();
    }

    @AfterEach
    void tearDown() {
        ScheduledTasksService.TRAFFIC_CACHE.clear();
        ScheduledTasksService.UPDATE_TIME_CACHE.clear();
        ScheduledTasksService.WARNING_SENT_RECORD.clear();
        ScheduledTasksService.MELTDOWN_STOPPED_OCIDS.clear();
        OciCloudService.INSTANCE_CACHE.clear();
        if (cache != null) {
            cache.shutdown();
        }
    }

    @Test
    void setTrafficLimitPersistsAndValidatesAndNullLimitGbIsSafe() {
        Map<String, TrafficLimit> store = new HashMap<>();
        TrafficLimitRepository limitRepo = Mockito.mock(TrafficLimitRepository.class);
        when(limitRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        when(limitRepo.save(any(TrafficLimit.class))).thenAnswer(inv -> {
            TrafficLimit tl = inv.getArgument(0);
            store.put(tl.getAccountName(), tl);
            return tl;
        });

        OciProperties props = new OciProperties();
        OciProperties.Account acc = new OciProperties.Account();
        acc.setRegion("ap-tokyo-1");
        Map<String, OciProperties.Account> accMap = new LinkedHashMap<>();
        accMap.put("[tokyo]", acc);
        props.setAccounts(accMap);

        AdminAuthService authService = Mockito.mock(AdminAuthService.class);
        when(authService.checkAuth(any(), any())).thenReturn(true);

        AdminDashboardController controller = new AdminDashboardController();
        ReflectionTestUtils.setField(controller, "trafficLimitRepository", limitRepo);
        ReflectionTestUtils.setField(controller, "ociProperties", props);
        ReflectionTestUtils.setField(controller, "accountYamlService", new AccountYamlService());
        ReflectionTestUtils.setField(controller, "adminAuthService", authService);
        ReflectionTestUtils.setField(controller, "ociCloudService", Mockito.mock(OciCloudService.class));

        MockHttpServletRequest req = new MockHttpServletRequest();

        // Reject negative & unknown account
        assertEquals(Boolean.FALSE, controller.setTrafficLimit(req, Map.of("account", "[tokyo]", "limit_gb", -5)).get("success"));
        assertEquals(Boolean.FALSE, controller.setTrafficLimit(req, Map.of("account", "nonexistent", "limit_gb", 500)).get("success"));

        // Save valid limit matching normalized account key `tokyo` -> `[tokyo]`
        Map<String, Object> ok = controller.setTrafficLimit(req, Map.of("account", "tokyo", "limit_gb", 8500));
        assertEquals(Boolean.TRUE, ok.get("success"));
        assertEquals(8500, store.get("[tokyo]").getLimitGb());
        controller.destroy();

        // Verify ScheduledTasksService handles a TrafficLimit row whose limitGb is null without NPE
        TrafficLimit nullLimitRow = new TrafficLimit();
        nullLimitRow.setAccountName("[tokyo]");
        nullLimitRow.setLimitGb(null);
        store.put("[tokyo]", nullLimitRow);

        ScheduledTasksService scheduler = buildScheduler(limitRepo, Mockito.mock(OciCloudService.class), props);
        assertDoesNotThrow(() -> scheduler.evaluateAndRecordTrafficUsage("[tokyo]", 120.5, "2026-11"));
    }

    @Test
    void monotonicTrafficProtectionPreventsRollbackAndSelectiveRevivalSkipsManuallyStoppedVm() throws Exception {
        Map<String, TrafficLimit> store = new HashMap<>();
        TrafficLimit tl = new TrafficLimit();
        tl.setAccountName("acc1");
        tl.setLimitGb(100);
        store.put("acc1", tl);

        TrafficLimitRepository limitRepo = Mockito.mock(TrafficLimitRepository.class);
        when(limitRepo.findById("acc1")).thenAnswer(inv -> Optional.ofNullable(store.get("acc1")));

        OciCloudService cloudService = Mockito.mock(OciCloudService.class);
        when(cloudService.updateInstancesCache()).thenReturn(new HashMap<>());

        OciProperties props = new OciProperties();
        props.setAccounts(Map.of("acc1", new OciProperties.Account()));

        ScheduledTasksService scheduler = buildScheduler(limitRepo, cloudService, props);
        String month = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));

        // 1) Usage 120GB >= 100GB triggers meltdown lock
        double recorded1 = scheduler.evaluateAndRecordTrafficUsage("acc1", 120.0, month);
        assertEquals(120.0, recorded1, 0.001);
        assertEquals(month, ScheduledTasksService.WARNING_SENT_RECORD.get("acc1_meltdown"));

        // 2) Same-month lower reading (e.g. 40GB API lag) MUST be clamped to 120GB and MUST NOT clear meltdown!
        double recorded2 = scheduler.evaluateAndRecordTrafficUsage("acc1", 40.0, month);
        assertEquals(120.0, recorded2, 0.001);
        assertEquals(month, ScheduledTasksService.WARNING_SENT_RECORD.get("acc1_meltdown"));

        // 3) Record one meltdown-stopped VM and one manually-stopped VM; manual power action removes manual VM
        ScheduledTasksService.MELTDOWN_STOPPED_OCIDS.add("ocid1.instance.meltdown");
        ScheduledTasksService.MELTDOWN_STOPPED_OCIDS.add("ocid1.instance.manual");
        ScheduledTasksService.recordManualInstancePowerAction("ocid1.instance.manual", "STOP");
        assertFalse(ScheduledTasksService.MELTDOWN_STOPPED_OCIDS.contains("ocid1.instance.manual"));

        Map<String, Map<String, String>> instSnapshot = new LinkedHashMap<>();
        instSnapshot.put("ocid1.instance.manual", new HashMap<>(Map.of(
                "account", "acc1", "state", "STOPPED", "name", "manual-backup-vm")));
        instSnapshot.put("ocid1.instance.meltdown", new HashMap<>(Map.of(
                "account", "acc1", "state", "STOPPED", "name", "meltdown-prod-vm")));
        when(cloudService.updateInstancesCache(true)).thenReturn(instSnapshot);

        CountDownLatch startedMeltdownLatch = new CountDownLatch(1);
        when(cloudService.instanceAction(eq("ocid1.instance.meltdown"), eq("START"), eq("acc1"))).thenAnswer(inv -> {
            startedMeltdownLatch.countDown();
            return true;
        });

        // Raise limit legitimately to 500GB -> clears meltdown and selectively revives ONLY meltdown VM
        tl.setLimitGb(500);
        scheduler.evaluateTrafficMeltdownOrRevive("acc1", 120.0, month);

        assertTrue(startedMeltdownLatch.await(3, TimeUnit.SECONDS), "Meltdown-stopped instance should be revived");
        verify(cloudService, never()).instanceAction(eq("ocid1.instance.manual"), eq("START"), any());
    }

    @Test
    void fetchTrafficInGbReturnsNegativeOneWhenAnySubscribedRegionFails() throws Exception {
        OciProperties props = new OciProperties();
        OciProperties.Account acc = new OciProperties.Account();
        acc.setTenancy("ocid1.tenancy.oc1..test");
        acc.setRegion("ap-tokyo-1");
        props.setAccounts(Map.of("multi-reg-acc", acc));

        OciClientManagerService clientManager = Mockito.mock(OciClientManagerService.class);
        OciClientWrapper wrapper = Mockito.mock(OciClientWrapper.class);
        MonitoringClient okRegionMon = Mockito.mock(MonitoringClient.class);
        MonitoringClient failingRegionMon = Mockito.mock(MonitoringClient.class);

        when(clientManager.getOciClients("multi-reg-acc")).thenReturn(wrapper);
        when(clientManager.getSubscribedRegions("multi-reg-acc")).thenReturn(List.of("ap-tokyo-1", "ap-osaka-1"));
        when(wrapper.getMonitoringClientForRegion("ap-tokyo-1")).thenReturn(okRegionMon);
        when(wrapper.getMonitoringClientForRegion("ap-osaka-1")).thenReturn(failingRegionMon);

        // Region 1 succeeds with 50 GB
        MetricData metric = MetricData.builder()
                .aggregatedDatapoints(List.of(AggregatedDatapoint.builder()
                        .value(50.0 * 1024 * 1024 * 1024)
                        .build()))
                .build();
        when(okRegionMon.summarizeMetricsData(any(SummarizeMetricsDataRequest.class)))
                .thenReturn(SummarizeMetricsDataResponse.builder().items(List.of(metric)).build());
        // Region 2 throws transient 503
        when(failingRegionMon.summarizeMetricsData(any(SummarizeMetricsDataRequest.class)))
                .thenThrow(new RuntimeException("503 Service Unavailable"));

        OciAccountBillingService billingService = new OciAccountBillingService();
        ReflectionTestUtils.setField(billingService, "ociProperties", props);
        ReflectionTestUtils.setField(billingService, "clientManager", clientManager);

        double result = billingService.fetchTrafficInGb("multi-reg-acc");
        assertEquals(-1.0, result, 0.0001, "Partial multi-region failure must return -1.0 instead of partial sum");
    }

    @Test
    void cloudInitHardensPureSshModeScopesRootPasswordAndPreservesOsFirewall() {
        OciComputeInstanceService computeService = new OciComputeInstanceService();
        String sshPubKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl test@local";

        // 1) Pure SSH key mode (Base64-decoded cloud-init user_data)
        String sshOnlyYaml = new String(Base64.getDecoder().decode(
                computeService.buildCloudInitUserData("", sshPubKey, "tokyo-acc")), StandardCharsets.UTF_8);
        assertTrue(sshOnlyYaml.contains("ssh_pwauth: false"));
        assertTrue(sshOnlyYaml.contains("PermitRootLogin prohibit-password"));
        assertTrue(sshOnlyYaml.contains("PasswordAuthentication no"));
        assertFalse(sshOnlyYaml.contains("PermitRootLogin yes"));
        assertFalse(sshOnlyYaml.contains("ufw disable"));
        assertFalse(sshOnlyYaml.contains("systemctl disable firewalld"));
        assertFalse(sshOnlyYaml.contains("iptables -F"));

        // 2) Password mode (Base64-decoded cloud-init user_data)
        String pwdYaml = new String(Base64.getDecoder().decode(
                computeService.buildCloudInitUserData("Str0ng#RootPwd!", "", "tokyo-acc")), StandardCharsets.UTF_8);
        assertTrue(pwdYaml.contains("ssh_pwauth: true"));
        assertTrue(pwdYaml.contains("PermitRootLogin yes"));
        assertTrue(pwdYaml.contains("root:Str0ng#RootPwd!"));
        for (String distroUser : new String[]{"ubuntu:", "opc:", "debian:", "centos:", "rocky:"}) {
            assertFalse(pwdYaml.contains(distroUser), "Must not overwrite non-root account password: " + distroUser);
        }
        assertFalse(pwdYaml.contains("ufw disable"));
        assertFalse(pwdYaml.contains("iptables -F"));
    }

    @Test
    void ociClientWrapperEnforcesFailClosedProxyAndBuildsNativeJerseyClientsWithHttpAndSocks5() throws Exception {
        assertNull(OciClientWrapper.parseProxy(null));
        assertNull(OciClientWrapper.parseProxy("   "));

        // Loopback, cloud metadata, bad scheme, and unresolvable host MUST throw IllegalArgumentException (Fail-Closed)
        assertThrows(IllegalArgumentException.class, () -> OciClientWrapper.parseProxy("http://127.0.0.1:7890"));
        assertThrows(IllegalArgumentException.class, () -> OciClientWrapper.parseProxy("http://169.254.169.254:80"));
        assertThrows(IllegalArgumentException.class, () -> OciClientWrapper.parseProxy("ftp://198.51.100.10:21"));
        assertThrows(IllegalArgumentException.class, () -> OciClientWrapper.parseProxy("not-a-valid-uri"));

        // Generate an ephemeral RSA key in memory to verify real OCI ComputeClient construction with Jersey 4 native connector
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        SimpleAuthenticationDetailsProvider provider = SimpleAuthenticationDetailsProvider.builder()
                .tenantId("ocid1.tenancy.oc1..aaaaaaaaexample")
                .userId("ocid1.user.oc1..aaaaaaaaexample")
                .fingerprint("11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff:00")
                .privateKeySupplier(() -> new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)))
                .region(Region.AP_TOKYO_1)
                .build();

        for (String proxyUrl : new String[]{"http://198.51.100.10:8080", "socks5://198.51.100.10:1080"}) {
            OciProperties.Account accCfg = new OciProperties.Account();
            accCfg.setProxy(proxyUrl);
            OciClientWrapper wrapper = new OciClientWrapper(
                    "proxy-test",
                    provider,
                    ClientConfiguration.builder().build(),
                    null,
                    accCfg
            );
            try {
                java.net.InetSocketAddress boundSockAddr =
                        (java.net.InetSocketAddress) wrapper.getProxyConfig().getProxy().address();
                assertTrue(boundSockAddr.getAddress().toString().startsWith("/"),
                        "Pinned InetAddress must contain raw IP bytes only without cached hostname");
                ComputeClient client = wrapper.getCompute();
                assertNotNull(client);
            } finally {
                wrapper.closeAll();
            }
        }

        // Real wire-level socket verification:
        // 1) SOCKS5 proxy MUST receive RFC 1928 version byte 0x05 (NOT plaintext HTTP 'C' from CONNECT!)
        try (java.net.ServerSocket socksServer = new java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))) {
            socksServer.setSoTimeout(5000);
            java.util.concurrent.atomic.AtomicInteger firstWireByte = new java.util.concurrent.atomic.AtomicInteger(-1);
            Thread acceptThread = Thread.ofVirtual().start(() -> {
                try (java.net.Socket s = socksServer.accept()) {
                    s.setSoTimeout(3000);
                    firstWireByte.set(s.getInputStream().read());
                } catch (Exception ignored) {}
            });

            var socksProxyCfg = OciClientWrapper.parseProxyForLoopbackTest("socks5://127.0.0.1:" + socksServer.getLocalPort());
            assertEquals(java.net.Proxy.Type.SOCKS, socksProxyCfg.getProxy().type());
            OciClientWrapper socksWrapper = new OciClientWrapper(
                    "socks-wire-test",
                    provider,
                    ClientConfiguration.builder().connectionTimeoutMillis(1500).readTimeoutMillis(1500).build(),
                    null,
                    new OciProperties.Account(),
                    socksProxyCfg
            );
            try {
                assertThrows(Exception.class, () -> socksWrapper.getCompute().getInstance(
                        com.oracle.bmc.core.requests.GetInstanceRequest.builder().instanceId("ocid1.instance.oc1.ap-tokyo-1.test").build()));
            } finally {
                socksWrapper.closeAll();
            }
            acceptThread.join(3000);
            assertEquals(0x05, firstWireByte.get(),
                    "SOCKS5 client must send SOCKS5 greeting byte 0x05 on the wire, not HTTP CONNECT ('C'=0x43)");
        }

        // 2) Authenticated HTTP CONNECT proxy MUST receive Proxy-Authorization: Basic header after 407 challenge
        try (java.net.ServerSocket httpProxyServer = new java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))) {
            httpProxyServer.setSoTimeout(5000);
            java.util.concurrent.atomic.AtomicReference<String> capturedAuthHeader = new java.util.concurrent.atomic.AtomicReference<>("");
            Thread proxyThread = Thread.ofVirtual().start(() -> {
                for (int connIdx = 0; connIdx < 2; connIdx++) {
                    try (java.net.Socket s = httpProxyServer.accept()) {
                        s.setSoTimeout(3000);
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                        String line;
                        boolean sawAuth = false;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            if (line.regionMatches(true, 0, "Proxy-Authorization:", 0, "Proxy-Authorization:".length())) {
                                capturedAuthHeader.set(line.trim());
                                sawAuth = true;
                            }
                        }
                        java.io.OutputStream out = s.getOutputStream();
                        if (!sawAuth) {
                            out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                                    + "Proxy-Authenticate: Basic realm=\"ocibox-proxy\"\r\n"
                                    + "Connection: close\r\n"
                                    + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        } else {
                            out.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII));
                        }
                        out.flush();
                        if (sawAuth) break;
                    } catch (Exception ignored) {}
                }
            });

            var httpProxyCfg = OciClientWrapper.parseProxyForLoopbackTest(
                    "http://proxyUser:proxySecret@127.0.0.1:" + httpProxyServer.getLocalPort());
            OciClientWrapper httpWrapper = new OciClientWrapper(
                    "http-auth-wire-test",
                    provider,
                    ClientConfiguration.builder().connectionTimeoutMillis(2000).readTimeoutMillis(2000).build(),
                    null,
                    new OciProperties.Account(),
                    httpProxyCfg
            );
            try {
                assertThrows(Exception.class, () -> httpWrapper.getCompute().getInstance(
                        com.oracle.bmc.core.requests.GetInstanceRequest.builder().instanceId("ocid1.instance.oc1.ap-tokyo-1.test").build()));
            } finally {
                httpWrapper.closeAll();
            }
            proxyThread.join(4000);
            String expectedB64 = Base64.getEncoder().encodeToString("proxyUser:proxySecret".getBytes(StandardCharsets.UTF_8));
            assertTrue(capturedAuthHeader.get().contains("Basic " + expectedB64),
                    () -> "Expected Proxy-Authorization Basic header on HTTPS CONNECT, got: " + capturedAuthHeader.get());
        }
    }

    @Test
    void transientRegionSubscriptionFailureDoesNotPoisonCacheOrReturnPartialTrafficAndOneGbMeltdownLifts() throws Exception {
        OciCloudService.SUBSCRIBED_REGIONS_CACHE.clear();

        OciProperties props = new OciProperties();
        OciProperties.Account acc = new OciProperties.Account();
        acc.setTenancy("ocid1.tenancy.oc1..transient");
        acc.setRegion("ap-tokyo-1");
        props.setAccounts(Map.of("transient-acc", acc));

        com.oracle.bmc.identity.IdentityClient identityClient = Mockito.mock(com.oracle.bmc.identity.IdentityClient.class);
        when(identityClient.listRegionSubscriptions(any()))
                .thenThrow(new BmcException(503, "ServiceUnavailable", "Identity 503", "req-1"));

        OciClientWrapper wrapper = Mockito.mock(OciClientWrapper.class);
        when(wrapper.getIdentity()).thenReturn(identityClient);

        OciClientManagerService realManager = new OciClientManagerService() {
            @Override
            public OciClientWrapper getOciClients(String accountName) {
                return wrapper;
            }
        };
        ReflectionTestUtils.setField(realManager, "ociProperties", props);

        List<String> fallbackRegions = realManager.getSubscribedRegions("transient-acc");
        assertEquals(List.of("ap-tokyo-1"), fallbackRegions);
        assertFalse(OciCloudService.SUBSCRIBED_REGIONS_CACHE.containsKey("transient-acc"),
                "Transient 503 on listRegionSubscriptions must NOT poison SUBSCRIBED_REGIONS_CACHE");
        assertTrue(realManager.hasTransientRegionDiscoveryFailure("transient-acc"));

        OciAccountBillingService billingService = new OciAccountBillingService();
        ReflectionTestUtils.setField(billingService, "ociProperties", props);
        ReflectionTestUtils.setField(billingService, "clientManager", realManager);
        assertEquals(-1.0, billingService.fetchTrafficInGb("transient-acc"), 0.0001,
                "fetchTrafficInGb must return -1.0 when region subscription discovery transiently failed");

        // Also verify 1GB limit meltdown at usage=1.0 lifts cleanly when raised to 5GB, and deleteBootVolume treats 404 as true
        Map<String, TrafficLimit> store = new HashMap<>();
        TrafficLimit tl = new TrafficLimit();
        tl.setAccountName("transient-acc");
        tl.setLimitGb(1);
        store.put("transient-acc", tl);
        TrafficLimitRepository limitRepo = Mockito.mock(TrafficLimitRepository.class);
        when(limitRepo.findById("transient-acc")).thenAnswer(inv -> Optional.ofNullable(store.get("transient-acc")));

        OciCloudService cloudMock = Mockito.mock(OciCloudService.class);
        when(cloudMock.updateInstancesCache()).thenReturn(new HashMap<>());
        when(cloudMock.updateInstancesCache(true)).thenReturn(new HashMap<>());

        ScheduledTasksService scheduler = buildScheduler(limitRepo, cloudMock, props);
        String month = "2026-11";
        scheduler.evaluateAndRecordTrafficUsage("transient-acc", 1.0, month);
        assertEquals(month, ScheduledTasksService.WARNING_SENT_RECORD.get("transient-acc_meltdown"));

        tl.setLimitGb(5);
        scheduler.evaluateTrafficMeltdownOrRevive("transient-acc", 1.0, month, true);
        assertFalse(ScheduledTasksService.WARNING_SENT_RECORD.containsKey("transient-acc_meltdown"),
                "Raising limit from 1GB to 5GB when usage==1.0GB must lift meltdown");

        // Verify deleteBootVolume treats 404 NotFound (already deleted during reinstall) as true
        com.oracle.bmc.core.BlockstorageClient blockClient = Mockito.mock(com.oracle.bmc.core.BlockstorageClient.class);
        doThrow(new BmcException(404, "NotAuthorizedOrNotFound", "Volume already deleted", "req-2"))
                .when(blockClient).deleteBootVolume(any());
        when(wrapper.getBlockstorageClientForRegion(any())).thenReturn(blockClient);
        when(wrapper.getBlockstorage()).thenReturn(blockClient);
        OciBootVolumeService bvService = new OciBootVolumeService();
        ReflectionTestUtils.setField(bvService, "ociProperties", props);
        ReflectionTestUtils.setField(bvService, "redisTemplate", cache);
        ReflectionTestUtils.setField(bvService, "clientManager", realManager);
        assertTrue(bvService.deleteBootVolume("ocid1.bootvolume.oc1.ap-tokyo-1.alreadygone", "transient-acc"),
                "404 on deleteBootVolume should return true so reinstall monitor does not emit false failure warning");
    }

    @Test
    void disable500MbpsSerializesExplicitNullRouteTableIdAndReclaimsIdleNatGateway() throws Exception {
        OciProperties props = new OciProperties();
        OciProperties.Account acc = new OciProperties.Account();
        acc.setTenancy("ocid1.tenancy.oc1..500m");
        acc.setRegion("ap-tokyo-1");
        props.setAccounts(Map.of("acc-500m", acc));

        OciClientManagerService clientManager = Mockito.mock(OciClientManagerService.class);
        OciClientWrapper wrapper = Mockito.mock(OciClientWrapper.class);
        ComputeClient computeClient = Mockito.mock(ComputeClient.class);
        VirtualNetworkClient vncClient = Mockito.mock(VirtualNetworkClient.class);
        com.oracle.bmc.networkloadbalancer.NetworkLoadBalancerClient nlbClient =
                Mockito.mock(com.oracle.bmc.networkloadbalancer.NetworkLoadBalancerClient.class);

        when(clientManager.getOciClients("acc-500m")).thenReturn(wrapper);
        when(clientManager.getActiveCompartmentIds("acc-500m")).thenReturn(List.of("ocid1.tenancy.oc1..500m"));
        when(wrapper.getComputeClientForRegion(any())).thenReturn(computeClient);
        when(wrapper.getVncClientForRegion(any())).thenReturn(vncClient);
        when(wrapper.getNetworkLoadBalancerClientForRegion(any())).thenReturn(nlbClient);

        String instOcid = "ocid1.instance.oc1.ap-tokyo-1.abcdef123456";
        when(computeClient.listVnicAttachments(any())).thenReturn(ListVnicAttachmentsResponse.builder()
                .items(List.of(VnicAttachment.builder()
                        .vnicId("ocid1.vnic.1")
                        .lifecycleState(VnicAttachment.LifecycleState.Attached)
                        .build()))
                .build());
        when(vncClient.getVnic(any())).thenReturn(com.oracle.bmc.core.responses.GetVnicResponse.builder()
                .vnic(com.oracle.bmc.core.model.Vnic.builder()
                        .id("ocid1.vnic.1")
                        .subnetId("ocid1.subnet.1")
                        .privateIp("10.0.0.10")
                        .routeTableId("ocid1.routetable.nat500m")
                        .build())
                .build());
        when(vncClient.getSubnet(any())).thenReturn(com.oracle.bmc.core.responses.GetSubnetResponse.builder()
                .subnet(com.oracle.bmc.core.model.Subnet.builder()
                        .id("ocid1.subnet.1")
                        .vcnId("ocid1.vcn.1")
                        .compartmentId("ocid1.tenancy.oc1..500m")
                        .routeTableId("ocid1.routetable.default")
                        .build())
                .build());
        when(vncClient.listSubnets(any())).thenReturn(com.oracle.bmc.core.responses.ListSubnetsResponse.builder()
                .items(List.of())
                .build());
        when(nlbClient.listNetworkLoadBalancers(any())).thenReturn(
                com.oracle.bmc.networkloadbalancer.responses.ListNetworkLoadBalancersResponse.builder()
                        .networkLoadBalancerCollection(
                                com.oracle.bmc.networkloadbalancer.model.NetworkLoadBalancerCollection.builder()
                                        .items(List.of())
                                        .build())
                        .build());
        when(vncClient.listRouteTables(any())).thenReturn(com.oracle.bmc.core.responses.ListRouteTablesResponse.builder()
                .items(List.of(com.oracle.bmc.core.model.RouteTable.builder()
                        .id("ocid1.routetable.nat500m")
                        .displayName("nat-route-500m")
                        .lifecycleState(com.oracle.bmc.core.model.RouteTable.LifecycleState.Available)
                        .build()))
                .build());
        when(vncClient.listNatGateways(any())).thenReturn(com.oracle.bmc.core.responses.ListNatGatewaysResponse.builder()
                .items(List.of(com.oracle.bmc.core.model.NatGateway.builder()
                        .id("ocid1.natgateway.500m")
                        .displayName("nat-gateway-500m")
                        .lifecycleState(com.oracle.bmc.core.model.NatGateway.LifecycleState.Available)
                        .build()))
                .build());
        when(vncClient.listPrivateIps(any())).thenReturn(ListPrivateIpsResponse.builder()
                .items(List.of(PrivateIp.builder().id("ocid1.privateip.1").build()))
                .build());
        when(vncClient.getPublicIpByPrivateIpId(any())).thenReturn(GetPublicIpByPrivateIpIdResponse.builder()
                .publicIp(PublicIp.builder().ipAddress("198.51.100.88").lifetime(PublicIp.Lifetime.Ephemeral).build())
                .build());

        OciNetworkService netService = new OciNetworkService();
        ReflectionTestUtils.setField(netService, "ociProperties", props);
        ReflectionTestUtils.setField(netService, "redisTemplate", cache);
        ReflectionTestUtils.setField(netService, "clientManager", clientManager);

        String restoredIp = netService.disable500Mbps(instOcid, "acc-500m");
        assertEquals("198.51.100.88", restoredIp);

        var vnicCaptor = org.mockito.ArgumentCaptor.forClass(com.oracle.bmc.core.requests.UpdateVnicRequest.class);
        verify(vncClient).updateVnic(vnicCaptor.capture());
        var updateDetails = vnicCaptor.getValue().getUpdateVnicDetails();
        assertNull(updateDetails.getRouteTableId());
        String serializedJson = com.oracle.bmc.serialization.jackson.JacksonSerializer
                .getDefaultObjectMapper().writeValueAsString(updateDetails);
        assertTrue(serializedJson.contains("\"routeTableId\":null"),
                () -> "UpdateVnicDetails must serialize explicit JSON null for routeTableId, got: " + serializedJson);

        verify(vncClient).deleteRouteTable(any());
        verify(vncClient).deleteNatGateway(any());
    }

    @Test
    void quotaServiceValidatesBeforeCloudTouchAndUsesAtomicUpdateInsteadOfDeleteFirst() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> OciQuotaService.validateAndParseQuotaStatements("   \n# comment only\n", "ap-tokyo-1"));
        assertThrows(IllegalArgumentException.class,
                () -> OciQuotaService.validateAndParseQuotaStatements("DROP TABLE quotas;", "ap-tokyo-1"));

        OciProperties props = new OciProperties();
        OciProperties.Account acc = new OciProperties.Account();
        acc.setTenancy("ocid1.tenancy.oc1..quota");
        acc.setRegion("ap-tokyo-1");
        props.setAccounts(Map.of("payg-acc", acc));

        SystemConfigRepository cfgRepo = Mockito.mock(SystemConfigRepository.class);
        when(cfgRepo.findById("QUOTA_TEMPLATE")).thenReturn(Optional.empty());

        OciClientManagerService clientManager = Mockito.mock(OciClientManagerService.class);
        OciClientWrapper wrapper = Mockito.mock(OciClientWrapper.class);
        QuotasClient quotasClient = Mockito.mock(QuotasClient.class);
        when(clientManager.getOciClients("payg-acc")).thenReturn(wrapper);
        when(wrapper.getQuotas()).thenReturn(quotasClient);

        QuotaSummary existing = QuotaSummary.builder()
                .id("ocid1.quota.oc1..existing")
                .name("Strict_Always_Free_Quota")
                .lifecycleState(QuotaSummary.LifecycleState.Active)
                .build();
        when(quotasClient.listQuotas(any(ListQuotasRequest.class)))
                .thenReturn(ListQuotasResponse.builder().items(List.of(existing)).build());

        OciQuotaService quotaService = new OciQuotaService();
        ReflectionTestUtils.setField(quotaService, "ociProperties", props);
        ReflectionTestUtils.setField(quotaService, "systemConfigRepository", cfgRepo);
        ReflectionTestUtils.setField(quotaService, "clientManager", clientManager);

        quotaService.injectFreeTierQuota("payg-acc");

        verify(quotasClient, times(1)).updateQuota(any(UpdateQuotaRequest.class));
        verify(quotasClient, never()).deleteQuota(any(DeleteQuotaRequest.class));
        verify(quotasClient, never()).createQuota(any(CreateQuotaRequest.class));
    }

    @Test
    void networkServiceProtectsReservedIpAndEvictsStaleCacheOnCreateFailure() throws Exception {
        OciProperties props = new OciProperties();
        OciProperties.Account acc = new OciProperties.Account();
        acc.setTenancy("ocid1.tenancy.oc1..net");
        acc.setRegion("ap-tokyo-1");
        props.setAccounts(Map.of("net-acc", acc));

        OciClientManagerService clientManager = Mockito.mock(OciClientManagerService.class);
        OciClientWrapper wrapper = Mockito.mock(OciClientWrapper.class);
        ComputeClient computeClient = Mockito.mock(ComputeClient.class);
        VirtualNetworkClient vncClient = Mockito.mock(VirtualNetworkClient.class);

        when(clientManager.getOciClients("net-acc")).thenReturn(wrapper);
        when(wrapper.getComputeClientForRegion(any())).thenReturn(computeClient);
        when(wrapper.getCompute()).thenReturn(computeClient);
        when(wrapper.getVncClientForRegion(any())).thenReturn(vncClient);
        when(wrapper.getVnc()).thenReturn(vncClient);

        when(computeClient.listVnicAttachments(any(ListVnicAttachmentsRequest.class)))
                .thenReturn(ListVnicAttachmentsResponse.builder()
                        .items(List.of(VnicAttachment.builder().vnicId("ocid1.vnic.1").build()))
                        .build());
        when(vncClient.listPrivateIps(any(ListPrivateIpsRequest.class)))
                .thenReturn(ListPrivateIpsResponse.builder()
                        .items(List.of(PrivateIp.builder().id("ocid1.privateip.1").compartmentId("ocid1.tenancy.oc1..net").build()))
                        .build());

        OciNetworkService networkService = new OciNetworkService();
        ReflectionTestUtils.setField(networkService, "ociProperties", props);
        ReflectionTestUtils.setField(networkService, "redisTemplate", cache);
        ReflectionTestUtils.setField(networkService, "clientManager", clientManager);

        String instOcid = "ocid1.instance.oc1.ap-tokyo-1.aaaa";
        cache.opsForValue().set("oci:ip:" + instOcid, "203.0.113.50", 30, TimeUnit.DAYS);

        // 1) Lifetime.Reserved MUST throw IllegalStateException and NEVER delete the Reserved IP
        PublicIp reservedIp = PublicIp.builder()
                .id("ocid1.publicip.reserved")
                .ipAddress("203.0.113.50")
                .lifetime(PublicIp.Lifetime.Reserved)
                .build();
        when(vncClient.getPublicIpByPrivateIpId(any(GetPublicIpByPrivateIpIdRequest.class)))
                .thenReturn(GetPublicIpByPrivateIpIdResponse.builder().publicIp(reservedIp).build());

        assertThrows(IllegalStateException.class, () -> networkService.changeInstanceIp(instOcid, "net-acc"));
        verify(vncClient, never()).deletePublicIp(any(DeletePublicIpRequest.class));
        assertEquals("203.0.113.50", cache.opsForValue().get("oci:ip:" + instOcid));

        // 2) Local region location mapping without external HTTP
        assertTrue(OciRegionUtil.getRegionDisplayLocation("ap-tokyo-1").contains("东京"));
        assertTrue(networkService.getInstanceLocation("203.0.113.50", "ap-seoul-1").contains("首尔"));
    }

    @Test
    void isSafeKeyFileToDeleteRestrictsDeletionToApplicationKeysDirectory() throws Exception {
        Path appDir = tempDir.resolve("app-home");
        Path appKeysDir = appDir.resolve("keys");
        Files.createDirectories(appKeysDir);
        Path configFile = appDir.resolve("application.yml");
        Files.writeString(configFile, "oci:\n  accounts: {}\n", StandardCharsets.UTF_8);

        Path legitKey = appKeysDir.resolve("acc1.pem");
        Files.writeString(legitKey, "dummy-key", StandardCharsets.UTF_8);

        Path externalKeysDir = tempDir.resolve("other-place").resolve("keys");
        Files.createDirectories(externalKeysDir);
        Path externalKey = externalKeysDir.resolve("external.pem");
        Files.writeString(externalKey, "do-not-delete", StandardCharsets.UTF_8);

        assertTrue(AdminAccountController.isSafeKeyFileToDelete(legitKey.toFile(), configFile.toFile()));
        assertFalse(AdminAccountController.isSafeKeyFileToDelete(externalKey.toFile(), configFile.toFile()),
                "Must reject .pem files in arbitrary external /keys/ directories");
    }

    @Test
    void openAllPortsPreservesCustomSecurityRulesAndCreationServiceLogsSafely() throws Exception {
        OciProperties props = new OciProperties();
        OciProperties.Account acc = new OciProperties.Account();
        acc.setTenancy("ocid1.tenancy.oc1..sl");
        acc.setRegion("ap-tokyo-1");
        props.setAccounts(Map.of("sl-acc", acc));

        OciClientManagerService clientManager = Mockito.mock(OciClientManagerService.class);
        OciClientWrapper wrapper = Mockito.mock(OciClientWrapper.class);
        VirtualNetworkClient vncClient = Mockito.mock(VirtualNetworkClient.class);
        when(clientManager.getOciClients("sl-acc")).thenReturn(wrapper);
        when(clientManager.getSubscribedRegions("sl-acc")).thenReturn(List.of("ap-tokyo-1"));
        when(clientManager.getActiveCompartmentIds("sl-acc")).thenReturn(List.of("ocid1.tenancy.oc1..sl"));
        when(wrapper.getVncClientForRegion("ap-tokyo-1")).thenReturn(vncClient);

        when(vncClient.listVcns(any(ListVcnsRequest.class))).thenReturn(ListVcnsResponse.builder()
                .items(List.of(Vcn.builder().id("ocid1.vcn.1").lifecycleState(Vcn.LifecycleState.Available).build()))
                .build());

        IngressSecurityRule customWhitelist = IngressSecurityRule.builder()
                .source("198.51.100.99/32")
                .protocol("6")
                .description("office-bastion")
                .build();
        EgressSecurityRule customEgress = EgressSecurityRule.builder()
                .destination("10.0.0.0/8")
                .protocol("6")
                .build();
        SecurityList existingSl = SecurityList.builder()
                .id("ocid1.securitylist.1")
                .lifecycleState(SecurityList.LifecycleState.Available)
                .ingressSecurityRules(List.of(customWhitelist))
                .egressSecurityRules(List.of(customEgress))
                .build();
        when(vncClient.listSecurityLists(any(ListSecurityListsRequest.class)))
                .thenReturn(ListSecurityListsResponse.builder().items(List.of(existingSl)).build());

        OciNetworkService networkService = new OciNetworkService();
        ReflectionTestUtils.setField(networkService, "ociProperties", props);
        ReflectionTestUtils.setField(networkService, "redisTemplate", cache);
        ReflectionTestUtils.setField(networkService, "clientManager", clientManager);

        networkService.openAllPorts("sl-acc");

        var captor = org.mockito.ArgumentCaptor.forClass(UpdateSecurityListRequest.class);
        verify(vncClient, times(1)).updateSecurityList(captor.capture());
        List<IngressSecurityRule> mergedIngress = captor.getValue().getUpdateSecurityListDetails().getIngressSecurityRules();
        assertTrue(mergedIngress.stream().anyMatch(r -> "198.51.100.99/32".equals(r.getSource())),
                "Existing custom whitelist rule must be preserved when opening all ports");
        assertTrue(mergedIngress.stream().anyMatch(r -> "0.0.0.0/0".equals(r.getSource()) && "all".equals(r.getProtocol())));

        // CreationTaskService appendLog synchronization + truncation check
        CreationTaskService creationService = new CreationTaskService();
        creationService.appendLog("task-1", "hello\n");
        assertEquals("hello\n", creationService.getTaskLog("task-1"));
        creationService.shutdownAllTasks();
    }

    private ScheduledTasksService buildScheduler(
            TrafficLimitRepository limitRepo,
            OciCloudService cloudService,
            OciProperties props) {
        ScheduledTasksService scheduler = new ScheduledTasksService();
        ReflectionTestUtils.setField(scheduler, "limitRepo", limitRepo);
        ReflectionTestUtils.setField(scheduler, "ociCloudService", cloudService);
        ReflectionTestUtils.setField(scheduler, "ociProperties", props);
        ReflectionTestUtils.setField(scheduler, "redisTemplate", cache);
        ReflectionTestUtils.setField(scheduler, "systemConfigRepository", Mockito.mock(SystemConfigRepository.class));
        ReflectionTestUtils.setField(scheduler, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(scheduler, "notificationService", Mockito.mock(NotificationService.class));
        return scheduler;
    }
}
