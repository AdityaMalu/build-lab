#pragma once

#include <cstdint>
#include <stdexcept>
#include <string>

struct Clock {
    virtual ~Clock() = default;
    virtual int64_t nowMillis() const = 0;
};

struct RateLimiter {
    virtual ~RateLimiter() = default;
    virtual bool tryAcquire(const std::string& clientId) = 0;
};

class TokenBucketLimiter : public RateLimiter {
public:
    TokenBucketLimiter(int capacity, double refillPerSecond, const Clock& clock) : clock_(clock) {
        // TODO validate arguments and set up per-client state
        (void)capacity;
        (void)refillPerSecond;
    }

    bool tryAcquire(const std::string& clientId) override {
        // TODO refill the client's bucket based on elapsed time, then try to take one token
        (void)clientId;
        throw std::logic_error("TODO");
    }

private:
    const Clock& clock_;
    // TODO: per-client state
};

class SlidingWindowLimiter : public RateLimiter {
public:
    SlidingWindowLimiter(int maxRequests, int64_t windowMillis, const Clock& clock) : clock_(clock) {
        // TODO validate arguments and set up per-client state
        (void)maxRequests;
        (void)windowMillis;
    }

    bool tryAcquire(const std::string& clientId) override {
        // TODO drop timestamps that fell out of the window, then decide
        (void)clientId;
        throw std::logic_error("TODO");
    }

private:
    const Clock& clock_;
    // TODO: per-client state
};
