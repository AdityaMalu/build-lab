package cache;

import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import testkit.Concurrent;
import testkit.Test;

import static testkit.Assert.*;

public class TtlLruCacheTest {

    static final class ManualTime implements TimeSource {
        final AtomicLong now = new AtomicLong(1_000);

        public long nowMillis() {
            return now.get();
        }

        void advance(long ms) {
            now.addAndGet(ms);
        }
    }

    static TtlLruCache<String, String> cache(int capacity, long ttl, ManualTime t) {
        return new TtlLruCache<>(capacity, ttl, t);
    }

    @Test("put then get; missing keys return null")
    public void basics() {
        TtlLruCache<String, String> c = cache(3, 0, new ManualTime());
        c.put("a", "1");
        assertEquals("1", c.get("a"));
        assertNull(c.get("nope"), "missing key");
        c.put("a", "2");
        assertEquals("2", c.get("a"), "put replaces");
        assertEquals(1, c.size());
    }

    @Test("evicts the least recently used entry; get refreshes recency")
    public void lru() {
        TtlLruCache<String, String> c = cache(3, 0, new ManualTime());
        c.put("a", "1");
        c.put("b", "2");
        c.put("c", "3");
        c.get("a");            // order is now b, c, a
        c.put("d", "4");       // evicts b
        assertNull(c.get("b"), "b was least recently used");
        assertEquals("1", c.get("a"));
        assertEquals("3", c.get("c"));
        assertEquals("4", c.get("d"));
        assertEquals(1L, (Object) c.stats().evictions());
    }

    @Test("replacing a key refreshes recency and never evicts")
    public void replaceRefreshes() {
        TtlLruCache<String, String> c = cache(2, 0, new ManualTime());
        c.put("a", "1");
        c.put("b", "2");
        c.put("a", "1b");      // a becomes most recent, no eviction
        assertEquals(0L, (Object) c.stats().evictions(), "replace must not evict");
        c.put("c", "3");       // evicts b, not a
        assertNull(c.get("b"));
        assertEquals("1b", c.get("a"));
    }

    @Test("expiry boundary: live until t+ttl, expired exactly at t+ttl")
    public void expiryBoundary() {
        ManualTime t = new ManualTime();
        TtlLruCache<String, String> c = cache(10, 500, t);
        c.put("k", "v");
        t.advance(499);
        assertEquals("v", c.get("k"), "1ms before expiry");
        t.advance(1);
        assertNull(c.get("k"), "exactly at expiry");
        assertEquals(1L, (Object) c.stats().expirations());
        assertEquals(0, c.size());
    }

    @Test("get does not extend the TTL")
    public void getDoesNotExtend() {
        ManualTime t = new ManualTime();
        TtlLruCache<String, String> c = cache(10, 1_000, t);
        c.put("k", "v");
        for (int i = 0; i < 9; i++) {
            t.advance(100);
            assertEquals("v", c.get("k"));
        }
        t.advance(100);
        assertNull(c.get("k"), "expires 1000ms after the write, not after the last read");
    }

    @Test("per-entry TTL; ttl <= 0 never expires")
    public void perEntryTtl() {
        ManualTime t = new ManualTime();
        TtlLruCache<String, String> c = cache(10, 1_000, t);
        c.put("short", "s", 100);
        c.put("forever", "f", 0);
        c.put("default", "d");
        t.advance(100);
        assertNull(c.get("short"));
        assertEquals("d", c.get("default"));
        t.advance(1_000_000_000L);
        assertEquals("f", c.get("forever"));
        assertNull(c.get("default"));
    }

    @Test("replacing a key resets its TTL")
    public void replaceResetsTtl() {
        ManualTime t = new ManualTime();
        TtlLruCache<String, String> c = cache(10, 1_000, t);
        c.put("k", "v1");
        t.advance(900);
        c.put("k", "v2");
        t.advance(900);
        assertEquals("v2", c.get("k"), "new TTL started at the replace");
    }

    @Test("a full cache drops expired entries before evicting live ones")
    public void expiredBeforeLru() {
        ManualTime t = new ManualTime();
        TtlLruCache<String, String> c = cache(3, 10_000, t);
        c.put("a", "1", 100);
        c.put("b", "2");
        c.put("c", "3");
        t.advance(50);
        assertEquals("1", c.get("a"));   // a is now most recently used...
        t.advance(150);                  // ...but expired
        c.put("d", "4");
        assertEquals("2", c.get("b"), "b must survive: an expired entry was available");
        assertEquals("3", c.get("c"));
        assertEquals("4", c.get("d"));
        CacheStats s = c.stats();
        assertEquals(0L, (Object) s.evictions(), "no live entry evicted");
        assertEquals(1L, (Object) s.expirations());
    }

    @Test("size counts only live entries; remove reports live removals")
    public void sizeAndRemove() {
        ManualTime t = new ManualTime();
        TtlLruCache<String, String> c = cache(10, 100, t);
        c.put("a", "1");
        c.put("b", "2", 0);
        assertEquals(2, c.size());
        t.advance(100);
        assertEquals(1, c.size(), "a expired");
        assertTrue(c.remove("b"));
        assertFalse(c.remove("b"), "already gone");
        assertFalse(c.remove("zzz"));
        assertEquals(0, c.size());
    }

    @Test("hits and misses are counted by get and computeIfAbsent")
    public void stats() {
        TtlLruCache<String, String> c = cache(10, 0, new ManualTime());
        c.get("x");                                  // miss
        c.put("x", "1");
        c.get("x");                                  // hit
        c.computeIfAbsent("y", k -> "loaded");       // miss
        c.computeIfAbsent("y", k -> "never");        // hit
        CacheStats s = c.stats();
        assertEquals(2L, (Object) s.hits());
        assertEquals(2L, (Object) s.misses());
        assertEquals("loaded", c.get("y"));
    }

    @Test("computeIfAbsent caches non-null results only")
    public void computeNull() {
        TtlLruCache<String, String> c = cache(10, 0, new ManualTime());
        AtomicInteger calls = new AtomicInteger();
        assertNull(c.computeIfAbsent("k", k -> {
            calls.incrementAndGet();
            return null;
        }), "null is returned");
        c.computeIfAbsent("k", k -> {
            calls.incrementAndGet();
            return null;
        });
        assertEquals(2, calls.get(), "null must not be cached");
        assertEquals(0, c.size());
    }

    @Test("a failing loader caches nothing and the next call retries")
    public void loaderFailure() {
        TtlLruCache<String, String> c = cache(10, 0, new ManualTime());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> c.computeIfAbsent("k", k -> {
                    throw new IllegalStateException("backend down");
                }));
        assertEquals("backend down", ex.getMessage(), "the loader's own exception reaches the caller");
        assertEquals("ok", c.computeIfAbsent("k", k -> "ok"), "retry after failure");
    }

    @Test(value = "single flight: 16 concurrent misses run the loader once", timeoutMillis = 15_000)
    public void singleFlight() throws Exception {
        TtlLruCache<String, String> c = cache(10, 0, new ManualTime());
        AtomicInteger loads = new AtomicInteger();
        Set<String> results = ConcurrentHashMap.newKeySet();
        Concurrent.run(16, i -> results.add(c.computeIfAbsent("hot", k -> {
            loads.incrementAndGet();
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "price-42";
        })));
        assertEquals(1, loads.get(), "the backend must be called exactly once");
        assertEquals(Set.of("price-42"), results, "every caller gets the loaded value");
    }

    @Test(value = "single flight: waiters receive the loader's exception", timeoutMillis = 15_000)
    public void singleFlightFailure() throws Exception {
        TtlLruCache<String, String> c = cache(10, 0, new ManualTime());
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger loads = new AtomicInteger();
        Concurrent.run(8, i -> {
            try {
                c.computeIfAbsent("bad", k -> {
                    loads.incrementAndGet();
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("boom");
                });
            } catch (IllegalStateException expected) {
                failures.incrementAndGet();
            }
        });
        assertEquals(1, loads.get(), "one load for all callers");
        assertEquals(8, failures.get(), "every caller sees the failure");
    }

    @Test("a slow load for one key does not block other keys")
    public void noGlobalLockDuringLoad() throws Exception {
        TtlLruCache<String, String> c = cache(10, 0, new ManualTime());
        c.put("fast", "1");
        Thread slow = new Thread(() -> c.computeIfAbsent("slow", k -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "2";
        }));
        slow.setDaemon(true);
        slow.start();
        Thread.sleep(100);
        long start = System.nanoTime();
        assertEquals("1", c.get("fast"));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 500, "get on another key waited " + ms + "ms for the slow loader");
        slow.join(3_000);
    }

    @Test("validation")
    public void validation() {
        ManualTime t = new ManualTime();
        assertThrows(IllegalArgumentException.class, () -> new TtlLruCache<String, String>(0, 10, t));
        assertThrows(IllegalArgumentException.class, () -> new TtlLruCache<String, String>(1, 10, null));
        TtlLruCache<String, String> c = cache(2, 10, t);
        assertThrows(IllegalArgumentException.class, () -> c.put(null, "v"));
        assertThrows(IllegalArgumentException.class, () -> c.put("k", null));
        assertThrows(IllegalArgumentException.class, () -> c.get(null));
        assertThrows(IllegalArgumentException.class, () -> c.computeIfAbsent("k", null));
    }

    @Test("never exceeds capacity under concurrent use")
    public void concurrentCapacity() throws Exception {
        ManualTime t = new ManualTime();
        TtlLruCache<Integer, Integer> c = new TtlLruCache<>(20, 0, t);
        Concurrent.run(8, i -> {
            Random rnd = new Random(i);
            for (int k = 0; k < 3_000; k++) {
                int key = rnd.nextInt(60);
                if (rnd.nextBoolean()) c.put(key, k);
                else c.get(key);
                if (k % 100 == 0) assertTrue(c.size() <= 20, "size exceeded capacity");
            }
        });
        assertTrue(c.size() <= 20);
    }
}
