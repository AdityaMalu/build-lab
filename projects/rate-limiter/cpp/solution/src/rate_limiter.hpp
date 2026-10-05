#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>

struct Clock {
    virtual ~Clock() = default;
    virtual int64_t nowMillis() const = 0;
};

struct RateLimiter {
    virtual ~RateLimiter() = default;
    virtual bool tryAcquire(const std::string& clientId) = 0;
};

namespace detail {
inline void requireClient(const std::string& clientId) {
    if (clientId.find_first_not_of(" \t\r\n") == std::string::npos) {
        throw std::invalid_argument("clientId required");
    }
}
}  // namespace detail

class TokenBucketLimiter : public RateLimiter {
public:
    TokenBucketLimiter(int capacity, double refillPerSecond, const Clock& clock)
        : capacity_(capacity), refillPerMilli_(refillPerSecond / 1000.0), clock_(clock) {
        if (capacity <= 0) throw std::invalid_argument("capacity must be positive");
        if (refillPerSecond < 0 || std::isnan(refillPerSecond)) throw std::invalid_argument("refill must be >= 0");
    }

    bool tryAcquire(const std::string& clientId) override {
        detail::requireClient(clientId);
        Bucket* b;
        {
            std::lock_guard<std::mutex> lock(mapMu_);  // guards the map only
            auto& slot = buckets_[clientId];
            if (!slot) slot = std::make_unique<Bucket>(static_cast<double>(capacity_), clock_.nowMillis());
            b = slot.get();
        }
        std::lock_guard<std::mutex> lock(b->mu);
        int64_t now = clock_.nowMillis();
        int64_t elapsed = std::max<int64_t>(0, now - b->lastRefill);
        b->tokens = std::min<double>(capacity_, b->tokens + static_cast<double>(elapsed) * refillPerMilli_);
        b->lastRefill = now;
        if (b->tokens >= 1.0 - 1e-9) {
            b->tokens = std::max(0.0, b->tokens - 1.0);
            return true;
        }
        return false;
    }

private:
    struct Bucket {
        Bucket(double t, int64_t now) : tokens(t), lastRefill(now) {}
        std::mutex mu;
        double tokens;
        int64_t lastRefill;
    };

    int capacity_;
    double refillPerMilli_;
    const Clock& clock_;
    std::mutex mapMu_;
    std::unordered_map<std::string, std::unique_ptr<Bucket>> buckets_;
};

class SlidingWindowLimiter : public RateLimiter {
public:
    SlidingWindowLimiter(int maxRequests, int64_t windowMillis, const Clock& clock)
        : max_(maxRequests), window_(windowMillis), clock_(clock) {
        if (maxRequests <= 0) throw std::invalid_argument("maxRequests must be positive");
        if (windowMillis <= 0) throw std::invalid_argument("windowMillis must be positive");
    }

    bool tryAcquire(const std::string& clientId) override {
        detail::requireClient(clientId);
        Log* log;
        {
            std::lock_guard<std::mutex> lock(mapMu_);
            auto& slot = logs_[clientId];
            if (!slot) slot = std::make_unique<Log>();
            log = slot.get();
        }
        std::lock_guard<std::mutex> lock(log->mu);
        int64_t now = clock_.nowMillis();
        while (!log->times.empty() && log->times.front() <= now - window_) log->times.pop_front();
        if (static_cast<int>(log->times.size()) < max_) {
            log->times.push_back(now);
            return true;
        }
        return false;
    }

private:
    struct Log {
        std::mutex mu;
        std::deque<int64_t> times;
    };

    int max_;
    int64_t window_;
    const Clock& clock_;
    std::mutex mapMu_;
    std::unordered_map<std::string, std::unique_ptr<Log>> logs_;
};
