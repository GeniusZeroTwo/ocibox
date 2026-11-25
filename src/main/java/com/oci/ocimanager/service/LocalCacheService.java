package com.oci.ocimanager.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 纯轻量化内存缓存服务（无缝替代 Redis），实现零外部依赖、低内存占用的单体运行。
 */
@Service
public class LocalCacheService {

    public static final int DEFAULT_MAX_ENTRIES = 20_000;

    private static final class CacheValue {
        final String value;
        final long expireAtMillis; // <= 0 表示永不过期

        CacheValue(String value, long expireAtMillis) {
            this.value = value;
            this.expireAtMillis = expireAtMillis;
        }

        boolean isExpired(long now) {
            return expireAtMillis > 0 && now > expireAtMillis;
        }

        boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }
    }

    private final int maxEntries;
    private final ConcurrentHashMap<String, CacheValue> store = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "local-cache-cleaner");
        t.setDaemon(true);
        return t;
    });

    public LocalCacheService() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public LocalCacheService(int maxEntries) {
        this.maxEntries = Math.max(64, maxEntries);
        cleaner.scheduleAtFixedRate(this::evictExpired, 60, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        cleaner.shutdownNow();
    }

    public int size() {
        return store.size();
    }

    public void evictExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    /**
     * 当键数触及上限且写入新键时，先清理已过期键；仍超限时优先淘汰最早过期的短期临时键（如未登录挑战码、限流计数），
     * 避免恶意高频请求撑爆 384MB 堆内存。
     */
    private void ensureCapacityForKey(String incomingKey) {
        if (incomingKey != null && store.containsKey(incomingKey)) {
            return;
        }
        if (store.size() < maxEntries) {
            return;
        }
        synchronized (this) {
            if (store.size() < maxEntries) {
                return;
            }
            evictExpired();
            if (store.size() < maxEntries) {
                return;
            }
            int targetEvict = Math.max(1, maxEntries / 10);
            List<Map.Entry<String, CacheValue>> ephemeral = new ArrayList<>();
            List<String> permanentKeys = new ArrayList<>();
            for (Map.Entry<String, CacheValue> entry : store.entrySet()) {
                if (isProtectedSecurityKey(entry.getKey())) {
                    continue;
                }
                if (entry.getValue().expireAtMillis > 0) {
                    ephemeral.add(entry);
                } else {
                    permanentKeys.add(entry.getKey());
                }
            }
            ephemeral.sort(Comparator
                    .comparingInt((Map.Entry<String, CacheValue> e) -> evictionPriority(e.getKey()))
                    .thenComparingLong(e -> e.getValue().expireAtMillis));

            int removed = 0;
            for (Map.Entry<String, CacheValue> e : ephemeral) {
                if (removed >= targetEvict && store.size() < maxEntries) {
                    break;
                }
                if (store.remove(e.getKey(), e.getValue())) {
                    removed++;
                }
            }
            if (store.size() >= maxEntries) {
                for (String k : permanentKeys) {
                    if (store.size() < maxEntries) break;
                    store.remove(k);
                }
            }
        }
    }

    private static boolean isProtectedSecurityKey(String key) {
        if (key == null) return false;
        return key.startsWith("oci:admin_session:")
                || key.startsWith("oci:admin_stepup:")
                || key.equals("oci:admin_login:global_code_fail")
                || key.equals("oci:admin_login:send_global")
                || key.startsWith("oci:admin_login:code:")
                || key.startsWith("oci:passkey:reg_challenge:")
                || key.startsWith("oci:passkey:reg_rpid:")
                || key.startsWith("oci:month_autostart_done:")
                || key.startsWith("oci:day_one_completed:");
    }

    private static int evictionPriority(String key) {
        if (key == null) return 0;
        if (key.startsWith("oci:passkey:login_")
                || key.startsWith("oci:passkey:opt_rate:")
                || key.startsWith("oci:passkey:global_opt_rate")
                || key.startsWith("oci:passkey:fail_alert_cooldown:")
                || key.startsWith("oci:tg_unauth_cd:")
                || key.startsWith("oci:ip_loc:")) {
            return 0;
        }
        if (key.startsWith("oci:admin_login:fail:")
                || key.startsWith("oci:admin_login:send:")
                || key.startsWith("oci:admin_login:lock:")) {
            return 1;
        }
        return 2;
    }

    public boolean hasKey(String key) {
        if (key == null) return false;
        CacheValue cv = store.get(key);
        if (cv == null) return false;
        if (cv.isExpired()) {
            store.remove(key, cv);
            return false;
        }
        return true;
    }

    public boolean delete(String key) {
        if (key == null) return false;
        return store.remove(key) != null;
    }

    public long delete(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return 0;
        long count = 0;
        for (String k : keys) {
            if (k != null && store.remove(k) != null) count++;
        }
        return count;
    }

    /**
     * 按通配符 (* / ?) 安全删除匹配键，所有字面量片段均通过 Pattern.quote 转义，
     * 防止含 []()+${}^| 等正则元字符的账号名触发 PatternSyntaxException 或越界误删。
     */
    public void deletePattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) return;
        Pattern compiled = compileGlobPattern(pattern);
        store.keySet().removeIf(k -> compiled.matcher(k).matches());
    }

    static Pattern compileGlobPattern(String glob) {
        StringBuilder regex = new StringBuilder("^");
        int segmentStart = 0;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' || c == '?') {
                if (i > segmentStart) {
                    regex.append(Pattern.quote(glob.substring(segmentStart, i)));
                }
                regex.append(c == '*' ? ".*" : ".");
                segmentStart = i + 1;
            }
        }
        if (segmentStart < glob.length()) {
            regex.append(Pattern.quote(glob.substring(segmentStart)));
        }
        regex.append("$");
        return Pattern.compile(regex.toString());
    }

    public boolean expire(String key, long timeout, TimeUnit unit) {
        if (key == null || unit == null) return false;
        long now = System.currentTimeMillis();
        long expireAt = now + Math.max(0L, unit.toMillis(timeout));
        CacheValue updated = store.computeIfPresent(key, (k, cv) -> {
            if (cv.isExpired(now)) {
                return null;
            }
            return new CacheValue(cv.value, expireAt);
        });
        return updated != null;
    }

    public Long getExpire(String key, TimeUnit unit) {
        if (key == null || unit == null) return -2L;
        CacheValue cv = store.get(key);
        long now = System.currentTimeMillis();
        if (cv == null || cv.isExpired(now)) {
            if (cv != null) store.remove(key, cv);
            return -2L;
        }
        if (cv.expireAtMillis <= 0) return -1L;
        long remain = cv.expireAtMillis - now;
        if (remain <= 0) {
            store.remove(key, cv);
            return -2L;
        }
        return unit.convert(remain, TimeUnit.MILLISECONDS);
    }

    public final ValueOperations opsForValue = new ValueOperations();

    public ValueOperations opsForValue() {
        return opsForValue;
    }

    public class ValueOperations {
        public String get(String key) {
            if (key == null) return null;
            CacheValue cv = store.get(key);
            if (cv == null) return null;
            if (cv.isExpired()) {
                store.remove(key, cv);
                return null;
            }
            return cv.value;
        }

        public void set(String key, String value) {
            if (key != null && value != null) {
                ensureCapacityForKey(key);
                store.put(key, new CacheValue(value, -1L));
            }
        }

        public void set(String key, String value, long timeout, TimeUnit unit) {
            if (key != null && value != null && unit != null) {
                ensureCapacityForKey(key);
                long expireAt = System.currentTimeMillis() + Math.max(0L, unit.toMillis(timeout));
                store.put(key, new CacheValue(value, expireAt));
            }
        }

        public Long increment(String key) {
            return increment(key, -1L, null);
        }

        /**
         * 原子自增并在键首次创建（或原键已过期）时同步绑定 TTL，彻底消除先 increment 后 expire 之间的永不过期竞态窗口。
         */
        public Long increment(String key, long timeoutIfCreated, TimeUnit unit) {
            if (key == null) return 0L;
            ensureCapacityForKey(key);
            AtomicLong resultHolder = new AtomicLong(0L);
            long now = System.currentTimeMillis();
            long defaultExpireAt = (timeoutIfCreated > 0 && unit != null)
                    ? (now + unit.toMillis(timeoutIfCreated))
                    : -1L;
            store.compute(key, (k, existing) -> {
                long val = 0L;
                long expireAt = defaultExpireAt;
                if (existing != null && !existing.isExpired(now)) {
                    try {
                        val = Long.parseLong(existing.value.trim());
                    } catch (Exception ignored) {}
                    expireAt = existing.expireAtMillis > 0 ? existing.expireAtMillis : defaultExpireAt;
                }
                val++;
                resultHolder.set(val);
                return new CacheValue(String.valueOf(val), expireAt);
            });
            return resultHolder.get();
        }
    }
}
