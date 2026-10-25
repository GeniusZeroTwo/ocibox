package com.oci.ocimanager.service;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * 纯轻量化内存缓存服务（无缝替代 Redis），实现零外部依赖、低内存占用的单体运行。
 */
@Service
public class LocalCacheService {

    private static class CacheValue {
        final String value;
        final long expireAtMillis; // <= 0 表示永不过期

        CacheValue(String value, long expireAtMillis) {
            this.value = value;
            this.expireAtMillis = expireAtMillis;
        }

        boolean isExpired() {
            return expireAtMillis > 0 && System.currentTimeMillis() > expireAtMillis;
        }
    }

    private final ConcurrentHashMap<String, CacheValue> store = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "local-cache-cleaner");
        t.setDaemon(true);
        return t;
    });

    public LocalCacheService() {
        // 每 60 秒定期清理过期键
        cleaner.scheduleAtFixedRate(this::evictExpired, 60, 60, TimeUnit.SECONDS);
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(entry -> entry.getValue().expireAtMillis > 0 && now > entry.getValue().expireAtMillis);
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
            if (store.remove(k) != null) count++;
        }
        return count;
    }

    public void deletePattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) return;
        String regex = pattern.replace(".", "\.").replace("*", ".*").replace("?", ".");
        Pattern p = Pattern.compile("^" + regex + "$");
        store.keySet().removeIf(k -> p.matcher(k).matches());
    }

    public boolean expire(String key, long timeout, TimeUnit unit) {
        CacheValue cv = store.get(key);
        if (cv == null || cv.isExpired()) return false;
        long expireAt = System.currentTimeMillis() + unit.toMillis(timeout);
        store.put(key, new CacheValue(cv.value, expireAt));
        return true;
    }

    public Long getExpire(String key, TimeUnit unit) {
        CacheValue cv = store.get(key);
        if (cv == null || cv.isExpired()) return -2L;
        if (cv.expireAtMillis <= 0) return -1L;
        long remain = cv.expireAtMillis - System.currentTimeMillis();
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
                store.put(key, new CacheValue(value, -1L));
            }
        }

        public void set(String key, String value, long timeout, TimeUnit unit) {
            if (key != null && value != null) {
                long expireAt = System.currentTimeMillis() + unit.toMillis(timeout);
                store.put(key, new CacheValue(value, expireAt));
            }
        }

        public synchronized Long increment(String key) {
            String current = get(key);
            long val = 0;
            if (current != null) {
                try {
                    val = Long.parseLong(current.trim());
                } catch (Exception ignored) {}
            }
            val++;
            CacheValue old = store.get(key);
            long expireAt = (old != null) ? old.expireAtMillis : -1L;
            store.put(key, new CacheValue(String.valueOf(val), expireAt));
            return val;
        }
    }
}
