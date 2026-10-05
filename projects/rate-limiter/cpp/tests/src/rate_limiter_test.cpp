#include <atomic>

#include "labtest.hpp"
#include "rate_limiter.hpp"

namespace {

struct ManualClock : Clock {
    std::atomic<int64_t> now{1'000'000};
    int64_t nowMillis() const override { return now.load(); }
    void advance(int64_t ms) { now += ms; }
};

}  // namespace

// ---------------- token bucket

LAB_TEST(RateLimiterTest, bucketStartsFull, "token bucket: starts full and allows exactly `capacity` requests") {
    ManualClock c;
    TokenBucketLimiter rl(5, 1.0, c);
    for (int i = 0; i < 5; i++) ASSERT_TRUE(rl.tryAcquire("a"), "request " + std::to_string(i + 1) + " should pass");
    ASSERT_FALSE(rl.tryAcquire("a"), "6th request should be rejected");
}

LAB_TEST(RateLimiterTest, bucketRefills, "token bucket: refills over time") {
    ManualClock c;
    TokenBucketLimiter rl(2, 2.0, c);  // 1 token every 500ms
    ASSERT_TRUE(rl.tryAcquire("a"), "first");
    ASSERT_TRUE(rl.tryAcquire("a"), "second");
    ASSERT_FALSE(rl.tryAcquire("a"), "bucket should be empty");
    c.advance(499);
    ASSERT_FALSE(rl.tryAcquire("a"), "not quite one token yet");
    c.advance(1);
    ASSERT_TRUE(rl.tryAcquire("a"), "one token after 500ms");
    ASSERT_FALSE(rl.tryAcquire("a"), "empty again");
}

LAB_TEST(RateLimiterTest, bucketFractional, "token bucket: fractional refill accumulates across calls") {
    ManualClock c;
    TokenBucketLimiter rl(1, 1.0, c);
    ASSERT_TRUE(rl.tryAcquire("a"), "first");
    for (int i = 1; i <= 4; i++) {
        c.advance(200);
        ASSERT_FALSE(rl.tryAcquire("a"), "only " + std::to_string(200 * i) + "ms elapsed");
    }
    c.advance(200);
    ASSERT_TRUE(rl.tryAcquire("a"), "1000ms in 200ms steps must add up to one token");
}

LAB_TEST(RateLimiterTest, bucketCapped, "token bucket: never exceeds capacity after a long idle period") {
    ManualClock c;
    TokenBucketLimiter rl(3, 10.0, c);
    c.advance(60'000);
    int allowed = 0;
    for (int i = 0; i < 10; i++) allowed += rl.tryAcquire("a") ? 1 : 0;
    ASSERT_EQ(3, allowed, "capacity caps the burst");
}

LAB_TEST(RateLimiterTest, bucketIsolation, "token bucket: clients are isolated") {
    ManualClock c;
    TokenBucketLimiter rl(1, 0.0, c);
    ASSERT_TRUE(rl.tryAcquire("a"), "a first");
    ASSERT_FALSE(rl.tryAcquire("a"), "a empty");
    ASSERT_TRUE(rl.tryAcquire("b"), "client b has its own bucket");
}

LAB_TEST(RateLimiterTest, bucketValidation, "token bucket: validates arguments") {
    ManualClock c;
    ASSERT_THROWS(std::invalid_argument, TokenBucketLimiter(0, 1, c), "capacity 0");
    ASSERT_THROWS(std::invalid_argument, TokenBucketLimiter(1, -1, c), "negative refill");
    TokenBucketLimiter rl(1, 1, c);
    ASSERT_THROWS(std::invalid_argument, rl.tryAcquire(""), "empty client");
    ASSERT_THROWS(std::invalid_argument, rl.tryAcquire("  "), "blank client");
}

LAB_TEST(RateLimiterTest, bucketConcurrency, "token bucket: 100 threads cannot exceed capacity") {
    ManualClock c;
    TokenBucketLimiter rl(50, 0.0, c);
    std::atomic<int> allowed{0};
    labtest::runConcurrently(100, [&](int) {
        for (int k = 0; k < 20; k++)
            if (rl.tryAcquire("hot")) allowed++;
    });
    ASSERT_EQ(50, allowed.load(), "exactly capacity requests may pass");
}

// ---------------- sliding window

LAB_TEST(RateLimiterTest, windowBasic, "sliding window: allows maxRequests then rejects") {
    ManualClock c;
    SlidingWindowLimiter rl(3, 1000, c);
    for (int i = 0; i < 3; i++) ASSERT_TRUE(rl.tryAcquire("a"), "request " + std::to_string(i + 1));
    ASSERT_FALSE(rl.tryAcquire("a"), "4th request should be rejected");
}

LAB_TEST(RateLimiterTest, windowSlides, "sliding window: old requests slide out exactly at the boundary") {
    ManualClock c;
    SlidingWindowLimiter rl(2, 1000, c);
    ASSERT_TRUE(rl.tryAcquire("a"), "t=0");
    c.advance(400);
    ASSERT_TRUE(rl.tryAcquire("a"), "t=400");
    c.advance(599);
    ASSERT_FALSE(rl.tryAcquire("a"), "t=999: both still inside the window");
    c.advance(1);
    ASSERT_TRUE(rl.tryAcquire("a"), "t=1000: first request left the window");
    ASSERT_FALSE(rl.tryAcquire("a"), "full again");
    c.advance(400);
    ASSERT_TRUE(rl.tryAcquire("a"), "t=1400: second request left the window");
}

LAB_TEST(RateLimiterTest, windowRejectedNotCounted, "sliding window: rejected requests do not count") {
    ManualClock c;
    SlidingWindowLimiter rl(1, 1000, c);
    ASSERT_TRUE(rl.tryAcquire("a"), "first");
    for (int i = 0; i < 5; i++) {
        c.advance(100);
        ASSERT_FALSE(rl.tryAcquire("a"), "rejected");
    }
    c.advance(500);
    ASSERT_TRUE(rl.tryAcquire("a"), "rejections must not extend the block");
}

LAB_TEST(RateLimiterTest, windowValidation, "sliding window: validates arguments") {
    ManualClock c;
    ASSERT_THROWS(std::invalid_argument, SlidingWindowLimiter(0, 1000, c), "maxRequests 0");
    ASSERT_THROWS(std::invalid_argument, SlidingWindowLimiter(1, 0, c), "window 0");
    SlidingWindowLimiter rl(1, 1000, c);
    ASSERT_THROWS(std::invalid_argument, rl.tryAcquire(""), "empty client");
}

LAB_TEST(RateLimiterTest, windowConcurrency, "sliding window: 100 threads cannot exceed the limit") {
    ManualClock c;
    SlidingWindowLimiter rl(40, 10'000, c);
    std::atomic<int> allowed{0};
    labtest::runConcurrently(100, [&](int) {
        for (int k = 0; k < 10; k++)
            if (rl.tryAcquire("hot")) allowed++;
    });
    ASSERT_EQ(40, allowed.load(), "exactly maxRequests may pass");
}
