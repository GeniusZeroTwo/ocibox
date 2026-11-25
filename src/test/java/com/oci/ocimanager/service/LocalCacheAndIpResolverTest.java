package com.oci.ocimanager.service;

import com.oci.ocimanager.config.ClientIpResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LocalCacheAndIpResolverTest {

    private LocalCacheService cache;

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.shutdown();
        }
    }

    @Test
    void deletePatternEscapesRegexMetacharactersSafely() {
        cache = new LocalCacheService(256);
        cache.opsForValue().set("oci:acc:[prod.1+(test)]$a:ip", "1.1.1.1");
        cache.opsForValue().set("oci:acc:[prod.1+(test)]$:other", "2.2.2.2");
        cache.opsForValue().set("oci:acc:prodX1test:ip", "3.3.3.3");

        assertDoesNotThrow(() -> cache.deletePattern("oci:acc:[prod.1+(test)]*"));

        assertNull(cache.opsForValue().get("oci:acc:[prod.1+(test)]$:other"));
        assertNull(cache.opsForValue().get("oci:acc:[prod.1+(test)]$a:ip"));
        assertEquals("3.3.3.3", cache.opsForValue().get("oci:acc:prodX1test:ip"));
    }

    @Test
    void atomicIncrementWithTtlHandlesConcurrencyAndPreservesExpiry() throws Exception {
        cache = new LocalCacheService(256);
        String key = "oci:rate:concurrent";
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                tasks.add(() -> cache.opsForValue().increment(key, 60, TimeUnit.SECONDS));
            }
            List<Future<Long>> results = pool.invokeAll(tasks);
            for (Future<Long> f : results) {
                assertNotNull(f.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals("100", cache.opsForValue().get(key));
        Long ttl = cache.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 60, "Expected positive TTL <= 60s, got " + ttl);
    }

    @Test
    void boundedCapacityEvictsTransientKeysWhilePreservingAdminSessionsAndSecurityGuards() {
        cache = new LocalCacheService(64);
        cache.opsForValue().set("oci:admin_session:keep-token", "1700000000000", 12, TimeUnit.HOURS);
        cache.opsForValue().set("oci:admin_stepup:keep-token", "1", 5, TimeUnit.MINUTES);
        cache.opsForValue().set("oci:admin_login:global_code_fail", "4", 3, TimeUnit.MINUTES);
        cache.opsForValue().set("oci:admin_login:send_global", "1", 1, TimeUnit.MINUTES);
        cache.opsForValue().set("oci:admin_login:code:203.0.113.5", "654321", 3, TimeUnit.MINUTES);

        for (int i = 0; i < 150; i++) {
            cache.opsForValue().set("oci:passkey:login_challenge:c-" + i, "val-" + i, 2, TimeUnit.MINUTES);
            cache.opsForValue().set("oci:admin_login:fail:198.51.100." + i, "1", 15, TimeUnit.MINUTES);
        }

        assertTrue(cache.size() <= 64, "Cache size should remain bounded <= 64, got " + cache.size());
        assertTrue(cache.hasKey("oci:admin_session:keep-token"), "Active admin session must not be evicted");
        assertTrue(cache.hasKey("oci:admin_stepup:keep-token"), "Active step-up window must not be evicted");
        assertEquals("4", cache.opsForValue().get("oci:admin_login:global_code_fail"),
                "Global dynamic-code brute-force counter must not be evicted by login-fail flood");
        assertTrue(cache.hasKey("oci:admin_login:send_global"), "Global send-code cooldown must not be evicted");
        assertEquals("654321", cache.opsForValue().get("oci:admin_login:code:203.0.113.5"),
                "Active dynamic login code must not be evicted");
    }

    @Test
    void clientIpResolverIgnoresHeadersWhenUntrustedOrDirectPublicPeer() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("198.51.100.25");
        req.addHeader("X-Forwarded-For", "203.0.113.9");
        req.addHeader("X-Real-IP", "203.0.113.10");

        assertEquals("198.51.100.25", ClientIpResolver.resolve(req, false, "10.0.0.0/8"));
        assertEquals("198.51.100.25", ClientIpResolver.resolve(req, true, "10.0.0.0/8"));
    }

    @Test
    void clientIpResolverExtractsRightmostUntrustedIpAcrossTrustedProxiesAndStopsOnGarbage() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        // Spoofed left IP (1.2.3.4), real client IP (203.0.113.88), CDN edge proxy (172.16.5.4)
        req.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.88, 172.16.5.4");

        assertEquals("203.0.113.88", ClientIpResolver.resolve(req, true, "172.16.0.0/12"));

        // Malformed rightmost hop must halt leftward traversal rather than trusting attacker prefix
        MockHttpServletRequest spoofReq = new MockHttpServletRequest();
        spoofReq.setRemoteAddr("127.0.0.1");
        spoofReq.addHeader("X-Forwarded-For", "1.2.3.4, not-an-ip!!, 172.16.5.4");
        spoofReq.addHeader("X-Real-IP", "198.51.100.77");
        assertEquals("198.51.100.77", ClientIpResolver.resolve(spoofReq, true, "172.16.0.0/12"));
    }

    @Test
    void clientIpResolverHandlesIpv6LoopbackCanonicalCompressionAndMultipleXffHeaders() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("::1");
        req.addHeader("X-Forwarded-For", "[2001:0db8:0000:0000:0000:0000:0000:0042]:51234");

        assertEquals("2001:db8::42", ClientIpResolver.resolve(req, true, ""));

        // Multiple X-Forwarded-For headers: attacker spoofs first header, trusted proxy appends second header
        MockHttpServletRequest multiHeaderReq = new MockHttpServletRequest();
        multiHeaderReq.setRemoteAddr("127.0.0.1");
        multiHeaderReq.addHeader("X-Forwarded-For", "1.1.1.1");
        multiHeaderReq.addHeader("X-Forwarded-For", "203.0.113.99, 172.16.0.5");

        assertEquals("203.0.113.99", ClientIpResolver.resolve(multiHeaderReq, true, "172.16.0.0/12"),
                "Must combine all X-Forwarded-For headers in order rather than reading only spoofed first header");
    }
}
