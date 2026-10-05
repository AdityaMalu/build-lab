import threading

from labtest import assert_equal, assert_false, assert_raises, assert_true, run_concurrently, test
from ratelimiter import SlidingWindowLimiter, TokenBucketLimiter


class ManualTime:
    def __init__(self):
        self._now = 1_000_000
        self._lock = threading.Lock()

    def now_millis(self):
        with self._lock:
            return self._now

    def advance(self, ms):
        with self._lock:
            self._now += ms


class RateLimiterTest:

    # ---------------- token bucket

    @test("token bucket: starts full and allows exactly `capacity` requests")
    def bucket_starts_full(self):
        rl = TokenBucketLimiter(5, 1.0, ManualTime())
        for i in range(5):
            assert_true(rl.try_acquire("a"), f"request {i + 1} should pass")
        assert_false(rl.try_acquire("a"), "6th request should be rejected")

    @test("token bucket: refills over time")
    def bucket_refills(self):
        t = ManualTime()
        rl = TokenBucketLimiter(2, 2.0, t)  # 1 token every 500ms
        assert_true(rl.try_acquire("a"))
        assert_true(rl.try_acquire("a"))
        assert_false(rl.try_acquire("a"), "bucket should be empty")
        t.advance(499)
        assert_false(rl.try_acquire("a"), "not quite one token yet")
        t.advance(1)
        assert_true(rl.try_acquire("a"), "one token after 500ms")
        assert_false(rl.try_acquire("a"))

    @test("token bucket: fractional refill accumulates across calls")
    def bucket_fractional(self):
        t = ManualTime()
        rl = TokenBucketLimiter(1, 1.0, t)
        assert_true(rl.try_acquire("a"))
        for i in range(4):
            t.advance(200)
            assert_false(rl.try_acquire("a"), f"only {200 * (i + 1)}ms elapsed")
        t.advance(200)
        assert_true(rl.try_acquire("a"), "1000ms in 200ms steps must add up to one token")

    @test("token bucket: never exceeds capacity after a long idle period")
    def bucket_capped(self):
        t = ManualTime()
        rl = TokenBucketLimiter(3, 10.0, t)
        t.advance(60_000)
        allowed = sum(1 for _ in range(10) if rl.try_acquire("a"))
        assert_equal(3, allowed, "capacity caps the burst")

    @test("token bucket: clients are isolated")
    def bucket_isolation(self):
        rl = TokenBucketLimiter(1, 0.0, ManualTime())
        assert_true(rl.try_acquire("a"))
        assert_false(rl.try_acquire("a"))
        assert_true(rl.try_acquire("b"), "client b has its own bucket")

    @test("token bucket: validates arguments")
    def bucket_validation(self):
        t = ManualTime()
        assert_raises(ValueError, lambda: TokenBucketLimiter(0, 1, t))
        assert_raises(ValueError, lambda: TokenBucketLimiter(1, -1, t))
        assert_raises(ValueError, lambda: TokenBucketLimiter(1, 1, None))
        rl = TokenBucketLimiter(1, 1, t)
        assert_raises(ValueError, lambda: rl.try_acquire(None))
        assert_raises(ValueError, lambda: rl.try_acquire("  "))

    @test("token bucket: 100 threads cannot exceed capacity")
    def bucket_concurrency(self):
        rl = TokenBucketLimiter(50, 0.0, ManualTime())
        allowed = []
        lock = threading.Lock()

        def worker(_):
            for _ in range(20):
                if rl.try_acquire("hot"):
                    with lock:
                        allowed.append(1)

        run_concurrently(100, worker)
        assert_equal(50, len(allowed), "exactly capacity requests may pass")

    # ---------------- sliding window

    @test("sliding window: allows max_requests then rejects")
    def window_basic(self):
        rl = SlidingWindowLimiter(3, 1000, ManualTime())
        assert_true(rl.try_acquire("a"))
        assert_true(rl.try_acquire("a"))
        assert_true(rl.try_acquire("a"))
        assert_false(rl.try_acquire("a"))

    @test("sliding window: old requests slide out exactly at the boundary")
    def window_slides(self):
        t = ManualTime()
        rl = SlidingWindowLimiter(2, 1000, t)
        assert_true(rl.try_acquire("a"))  # t=0
        t.advance(400)
        assert_true(rl.try_acquire("a"))  # t=400
        t.advance(599)
        assert_false(rl.try_acquire("a"), "t=999: both still inside the window")
        t.advance(1)
        assert_true(rl.try_acquire("a"), "t=1000: first request left the window")
        assert_false(rl.try_acquire("a"))
        t.advance(400)
        assert_true(rl.try_acquire("a"), "t=1400: second request left the window")

    @test("sliding window: rejected requests do not count")
    def window_rejected_not_counted(self):
        t = ManualTime()
        rl = SlidingWindowLimiter(1, 1000, t)
        assert_true(rl.try_acquire("a"))
        for _ in range(5):
            t.advance(100)
            assert_false(rl.try_acquire("a"))
        t.advance(500)
        assert_true(rl.try_acquire("a"), "rejections must not extend the block")

    @test("sliding window: validates arguments")
    def window_validation(self):
        t = ManualTime()
        assert_raises(ValueError, lambda: SlidingWindowLimiter(0, 1000, t))
        assert_raises(ValueError, lambda: SlidingWindowLimiter(1, 0, t))
        assert_raises(ValueError, lambda: SlidingWindowLimiter(1, 1000, None))
        assert_raises(ValueError, lambda: SlidingWindowLimiter(1, 1000, t).try_acquire(""))

    @test("sliding window: 100 threads cannot exceed the limit")
    def window_concurrency(self):
        rl = SlidingWindowLimiter(40, 10_000, ManualTime())
        allowed = []
        lock = threading.Lock()

        def worker(_):
            for _ in range(10):
                if rl.try_acquire("hot"):
                    with lock:
                        allowed.append(1)

        run_concurrently(100, worker)
        assert_equal(40, len(allowed), "exactly max_requests may pass")
