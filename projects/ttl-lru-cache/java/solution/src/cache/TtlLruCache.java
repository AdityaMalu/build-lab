package cache;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

public class TtlLruCache<K, V> {

    private record Entry<V>(V value, long expiresAt) {}

    private final int capacity;
    private final long defaultTtl;
    private final TimeSource time;
    /** access order: the first key is the least recently used */
    private final LinkedHashMap<K, Entry<V>> map = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<K, CompletableFuture<V>> inflight = new HashMap<>();
    private long hits, misses, evictions, expirations;

    public TtlLruCache(int capacity, long defaultTtlMillis, TimeSource time) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        if (time == null) throw new IllegalArgumentException("time required");
        this.capacity = capacity;
        this.defaultTtl = defaultTtlMillis;
        this.time = time;
    }

    public synchronized V get(K key) {
        requireKey(key);
        Entry<V> e = live(key, time.nowMillis());
        if (e == null) {
            misses++;
            return null;
        }
        hits++;
        return e.value();
    }

    public void put(K key, V value) {
        put(key, value, defaultTtl);
    }

    public synchronized void put(K key, V value, long ttlMillis) {
        requireKey(key);
        if (value == null) throw new IllegalArgumentException("value required");
        long now = time.nowMillis();
        Entry<V> old = map.remove(key);
        if (old != null && now >= old.expiresAt()) expirations++;
        if (old == null) makeRoom(now);
        map.put(key, new Entry<>(value, expiry(now, ttlMillis)));
    }

    public synchronized boolean remove(K key) {
        requireKey(key);
        Entry<V> e = map.remove(key);
        if (e == null) return false;
        if (time.nowMillis() >= e.expiresAt()) {
            expirations++;
            return false;
        }
        return true;
    }

    public synchronized int size() {
        purgeExpired(time.nowMillis());
        return map.size();
    }

    public V computeIfAbsent(K key, Function<? super K, ? extends V> loader) {
        requireKey(key);
        if (loader == null) throw new IllegalArgumentException("loader required");
        CompletableFuture<V> mine = null;
        CompletableFuture<V> theirs;
        synchronized (this) {
            Entry<V> e = live(key, time.nowMillis());
            if (e != null) {
                hits++;
                return e.value();
            }
            misses++;
            theirs = inflight.get(key);
            if (theirs == null) {
                mine = new CompletableFuture<>();
                inflight.put(key, mine);
            }
        }
        if (mine == null) return await(theirs);

        // we are the loader; run it without holding the cache lock
        try {
            V value = loader.apply(key);
            synchronized (this) {
                inflight.remove(key);
                if (value != null) put(key, value, defaultTtl);
            }
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error ex) {
            synchronized (this) {
                inflight.remove(key);
            }
            mine.completeExceptionally(ex);
            throw ex;
        }
    }

    public synchronized CacheStats stats() {
        return new CacheStats(hits, misses, evictions, expirations);
    }

    // ------------------------------------------------------------ internals

    /** Live entry for key (refreshing its recency), removing it if expired. */
    private Entry<V> live(K key, long now) {
        Entry<V> e = map.get(key);
        if (e == null) return null;
        if (now >= e.expiresAt()) {
            map.remove(key);
            expirations++;
            return null;
        }
        return e;
    }

    private void makeRoom(long now) {
        if (map.size() < capacity) return;
        purgeExpired(now);
        while (map.size() >= capacity) {
            K eldest = map.keySet().iterator().next();
            map.remove(eldest);
            evictions++;
        }
    }

    private void purgeExpired(long now) {
        Iterator<Map.Entry<K, Entry<V>>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (now >= it.next().getValue().expiresAt()) {
                it.remove();
                expirations++;
            }
        }
    }

    private static long expiry(long now, long ttl) {
        if (ttl <= 0) return Long.MAX_VALUE;
        long t = now + ttl;
        return t < now ? Long.MAX_VALUE : t;
    }

    private static void requireKey(Object key) {
        if (key == null) throw new IllegalArgumentException("key required");
    }

    private static <V> V await(CompletableFuture<V> f) {
        try {
            return f.join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error er) throw er;
            throw ce;
        }
    }
}
