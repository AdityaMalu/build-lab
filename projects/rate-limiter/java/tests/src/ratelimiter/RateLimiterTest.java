package ratelimiter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import testkit.Concurrent;
import testkit.Test;

import static testkit.Assert.*;

public class RateLimiterTest {

    static final class ManualTime implements TimeSource {
        final AtomicLong now = new AtomicLong(1_000_000);

        public long nowMillis() {
            return now.get();
        }

        void advance(long ms) {
            now.addAndGet(ms);
        }
    }

    // ---------------- token bucket

    @Test("token bucket: starts full and allows exactly `capacity` requests")
    public void bucketStartsFull() {
        ManualTime t = new ManualTime();
        RateLimiter rl = new TokenBucketLimiter(5, 1.0, t);
        for (int i = 0; i < 5; i++) assertTrue(rl.tryAcquire("a"), "request " + (i + 1) + " should pass");
        assertFalse(rl.tryAcquire("a"), "6th request should be rejected");
    }

    @Test("token bucket: refills over time")
    public void bucketRefills() {
        ManualTime t = new ManualTime();
        RateLimiter rl = new TokenBucketLimiter(2, 2.0, t); // 1 token every 500ms
        assertTrue(rl.tryAcquire("a"));
        assertTrue(rl.tryAcquire("a"));
        assertFalse(rl.tryAcquire("a"), "bucket should be empty");
        t.advance(499);
        assertFalse(rl.tryAcquire("a"), "not quite one token yet");
        t.advance(1);
        assertTrue(rl.tryAcquire("a"), "one token after 500ms");
        assertFalse(rl.tryAcquire("a"));
    }

    @Test("token bucket: fractional refill accumulates across calls")
    public void bucketFractional() {
        ManualTime t = new ManualTime();
        RateLimiter rl = new TokenBucketLimiter(1, 1.0, t);
        assertTrue(rl.tryAcquire("a"));
        for (int i = 0; i < 4; i++) {
            t.advance(200);
            assertFalse(rl.tryAcquire("a"), "only " + (200 * (i + 1)) + "ms elapsed");
        }
        t.advance(200);
        assertTrue(rl.tryAcquire("a"), "1000ms in 200ms steps must add up to one token");
    }

    @Test("token bucket: never exceeds capacity after a long idle period")
    public void bucketCapped() {
        ManualTime t = new ManualTime();
        RateLimiter rl = new TokenBucketLimiter(3, 10.0, t);
        t.advance(60_000);
        int allowed = 0;
        for (int i = 0; i < 10; i++) if (rl.tryAcquire("a")) allowed++;
        assertEquals(3, allowed, "capacity caps the burst");
    }

    @Test("token bucket: clients are isolated")
    public void bucketIsolation() {
        ManualTime t = new ManualTime();
        RateLimiter rl = new TokenBucketLimiter(1, 0.0, t);
        assertTrue(rl.tryAcquire("a"));
        assertFalse(rl.tryAcquire("a"));
        assertTrue(rl.tryAcquire("b"), "client b has its own bucket");
    }

    @Test("token bucket: validates arguments")
    public void bucketValidation() {
        ManualTime t = new ManualTime();
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketLimiter(0, 1, t));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketLimiter(1, -1, t));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketLimiter(1, 1, null));
        RateLimiter rl = new TokenBucketLimiter(1, 1, t);
        assertThrows(IllegalArgumentException.class, () -> rl.tryAcquire(null));
        assertThrows(IllegalArgumentException.class, () -> rl.tryAcquire("  "));
    }

    @Test("token bucket: 100 threads cannot exceed capacity")
    public void bucketConcurrency() throws Exception {
        ManualTime t = new ManualTime();
        RateLimiter rl = new TokenBucketLimiter(50, 0.0, t);
        AtomicInteger allowed = new AtomicInteger();
        Concurrent.run(100, i -> {
            for (int k = 0; k < 20; k++) if (rl.tryAcquire("hot")) allowed.incrementAndGet();
        });
        assertEquals(50, allowed.get(), "exactly capacity requests may pass");
    }

    // ---------------- sliding window

    @Test("sliding window: allows maxRequests then rejects")
    public void windowBasic() {
        ManualTime t = new ManualTime();
        RateLimiter rl = new SlidingWindowLimiter(3, 1000, t);
        assertTrue(rl.tryAcquire("a"));
        assertTrue(rl.tryAcquire("a"));
        assertTrue(rl.tryAcquire("a"));
        assertFalse(rl.tryAcquire("a"));
    }

    @Test("sliding window: old requests slide out exactly at the boundary")
    public void windowSlides() {
        ManualTime t = new ManualTime();
        RateLimiter rl = new SlidingWindowLimiter(2, 1000, t);
        assertTrue(rl.tryAcquire("a"));      // t=0
        t.advance(400);
        assertTrue(rl.tryAcquire("a"));      // t=400
        t.advance(599);
        assertFalse(rl.tryAcquire("a"), "t=999: both still inside the window");
        t.advance(1);
        assertTrue(rl.tryAcquire("a"), "t=1000: first request left the window");
        assertFalse(rl.tryAcquire("a"));
        t.advance(400);
        assertTrue(rl.tryAcquire("a"), "t=1400: second request left the window");
    }

    @Test("sliding window: rejected requests do not count")
    public void windowRejectedNotCounted() {
        ManualTime t = new ManualTime();
        RateLimiter rl = new SlidingWindowLimiter(1, 1000, t);
        assertTrue(rl.tryAcquire("a"));
        for (int i = 0; i < 5; i++) {
            t.advance(100);
            assertFalse(rl.tryAcquire("a"));
        }
        t.advance(500); // t=1000 since the only allowed request
        assertTrue(rl.tryAcquire("a"), "rejections must not extend the block");
    }

    @Test("sliding window: validates arguments")
    public void windowValidation() {
        ManualTime t = new ManualTime();
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowLimiter(0, 1000, t));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowLimiter(1, 0, t));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowLimiter(1, 1000, null));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowLimiter(1, 1000, t).tryAcquire(""));
    }

    @Test("sliding window: 100 threads cannot exceed the limit")
    public void windowConcurrency() throws Exception {
        ManualTime t = new ManualTime();
        RateLimiter rl = new SlidingWindowLimiter(40, 10_000, t);
        AtomicInteger allowed = new AtomicInteger();
        Concurrent.run(100, i -> {
            for (int k = 0; k < 10; k++) if (rl.tryAcquire("hot")) allowed.incrementAndGet();
        });
        assertEquals(40, allowed.get(), "exactly maxRequests may pass");
    }
}
