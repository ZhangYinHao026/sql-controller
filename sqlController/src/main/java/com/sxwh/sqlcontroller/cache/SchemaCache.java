package com.sxwh.sqlcontroller.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 元数据缓存：TTL 三种语义 + 容量淘汰 + 同 key 并发合并。
 *
 * <p>TTL 由 {@code ttlSupplier} 每次读取（配置改了立刻生效）：
 * 负数 = 永不过期、0 = 关闭缓存（不读不写、也不合并并发）、正数 = 到期重查。
 *
 * <p>容量：超过 {@code maxEntries} 时按写入顺序淘汰最旧；{@code maxEntries <= 0} 表示不限
 * （联想/快照这类按连接一条的缓存用不上容量保护，单表结构才需要）。
 *
 * <p>失败不入缓存：{@link #getOrLoad} 里 loader 抛异常时原样抛出，不写条目。
 */
public class SchemaCache<V> {

    private final LongSupplier ttlSupplier;
    private final int maxEntries;
    private final Map<String, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<V>> loading = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public SchemaCache(LongSupplier ttlSupplier, int maxEntries) {
        this.ttlSupplier = ttlSupplier;
        this.maxEntries = maxEntries;
    }

    /** 读缓存，未命中返回 null（不触发加载）。 */
    public V get(String key) {
        return get(key, ttlSupplier.getAsLong());
    }

    /** 写缓存；TTL 为 0（缓存关闭）时忽略。 */
    public void put(String key, V value) {
        put(key, value, ttlSupplier.getAsLong());
    }

    /** 命中即返回；未命中只加载一次，并发请求合并成一次，失败不写缓存。 */
    public V getOrLoad(String key, Supplier<V> loader) {
        return load(key, loader, false);
    }

    /** 跳过缓存读、强制重新加载；并发请求仍合并，成功后覆盖条目，失败保留旧条目。 */
    public V reload(String key, Supplier<V> loader) {
        return load(key, loader, true);
    }

    public void evict(String key) {
        entries.remove(key);
    }

    /** 按条目条件清理（连接级失效用：key 前缀 + 值里的真实表名）。 */
    public void evictIf(Predicate<Entry<V>> matcher) {
        entries.entrySet().removeIf(e -> matcher.test(e.getValue()));
    }

    /** 抓取时间（毫秒）；未命中或已过期返回 null。 */
    public Long capturedAt(String key) {
        Entry<V> entry = freshEntry(key, ttlSupplier.getAsLong());
        return entry == null ? null : entry.capturedAt;
    }

    /** 未过期的条目快照（调用方只读；精确 key 未命中时才用它做大小写兜底）。 */
    public List<Entry<V>> freshEntries() {
        long ttl = ttlSupplier.getAsLong();
        List<Entry<V>> out = new ArrayList<>();
        for (Entry<V> entry : entries.values()) {
            if (!isExpired(entry, ttl)) {
                out.add(entry);
            }
        }
        return out;
    }

    private V load(String key, Supplier<V> loader, boolean skipRead) {
        long ttl = ttlSupplier.getAsLong();
        if (ttl == 0) {
            // 缓存关闭：每次真查，也不合并并发（保持“关掉就是每次都查”的语义）
            return loader.get();
        }
        if (!skipRead) {
            V cached = get(key, ttl);
            if (cached != null) {
                return cached;
            }
        }
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> running = loading.putIfAbsent(key, mine);
        if (running != null) {
            return await(running);
        }
        try {
            V value = loader.get();
            put(key, value, ttl);
            mine.complete(value);
            return value;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            loading.remove(key, mine);
        }
    }

    private V get(String key, long ttl) {
        Entry<V> entry = freshEntry(key, ttl);
        return entry == null ? null : entry.value;
    }

    private Entry<V> freshEntry(String key, long ttl) {
        if (ttl == 0) {
            return null;
        }
        Entry<V> entry = entries.get(key);
        return entry == null || isExpired(entry, ttl) ? null : entry;
    }

    private boolean isExpired(Entry<V> entry, long ttl) {
        return ttl > 0 && System.currentTimeMillis() > entry.expireAt;
    }

    private void put(String key, V value, long ttl) {
        if (ttl == 0 || value == null) {
            return;
        }
        long now = System.currentTimeMillis();
        entries.put(key, new Entry<>(key, value, now, ttl < 0 ? -1L : now + ttl * 1000L, seq.incrementAndGet()));
        trim();
    }

    /** 只在超限时扫描（表结构缓存 2000 条量级，成本可忽略）。 */
    private void trim() {
        if (maxEntries <= 0) {
            return;
        }
        while (entries.size() > maxEntries) {
            Entry<V> oldest = null;
            for (Entry<V> entry : entries.values()) {
                if (oldest == null || entry.seq < oldest.seq) {
                    oldest = entry;
                }
            }
            if (oldest == null || entries.remove(oldest.key) == null) {
                return;
            }
        }
    }

    private V await(CompletableFuture<V> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("缓存加载被中断: " + e.getMessage(), e);
        }
    }

    /** 缓存条目：capturedAt 给前端显示「这份数据有多旧」，seq 用于容量淘汰。 */
    public static final class Entry<V> {
        public final String key;
        public final V value;
        public final long capturedAt;
        private final long expireAt;
        private final long seq;

        Entry(String key, V value, long capturedAt, long expireAt, long seq) {
            this.key = key;
            this.value = value;
            this.capturedAt = capturedAt;
            this.expireAt = expireAt;
            this.seq = seq;
        }
    }
}
