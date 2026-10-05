package cache;

import java.util.function.Function;

public class TtlLruCache<K, V> {

    public TtlLruCache(int capacity, long defaultTtlMillis, TimeSource time) {
        // TODO validate and set up storage (hint: an access-ordered LinkedHashMap)
    }

    public V get(K key) {
        throw new UnsupportedOperationException("TODO");
    }

    public void put(K key, V value) {
        throw new UnsupportedOperationException("TODO");
    }

    public void put(K key, V value, long ttlMillis) {
        throw new UnsupportedOperationException("TODO");
    }

    public boolean remove(K key) {
        throw new UnsupportedOperationException("TODO");
    }

    public int size() {
        throw new UnsupportedOperationException("TODO");
    }

    public V computeIfAbsent(K key, Function<? super K, ? extends V> loader) {
        // TODO single flight: concurrent callers for the same missing key share one load
        throw new UnsupportedOperationException("TODO");
    }

    public CacheStats stats() {
        throw new UnsupportedOperationException("TODO");
    }
}
