#pragma once

#include <chrono>
#include <cstdlib>
#include <functional>
#include <map>

#include "reset_support.hpp"

// Fixes: correct config key with validation; zero-padded fixed-length codes; expiry from the injected clock;
// describeExpiry honours the offset; expired at now >= expiry; password validated first; hash stored; code consumed;
// at most 5 wrong guesses; constant-time comparison; one mutex for check-and-consume.
class PasswordResetService {
public:
    PasswordResetService(std::map<std::string, std::string> config, const Clock& clock, UserStore& users,
                         std::function<long long(long long)> codes)
        : clock_(clock), users_(users), codes_(std::move(codes)) {
        if (!codes_) throw std::invalid_argument("codes required");
        ttlMillis_ = setting(config, "reset.ttl.minutes", 15, 1, 10'000'000) * 60'000;
        codeLength_ = static_cast<int>(setting(config, "reset.code.length", 6, 4, 10));
        offset_ = static_cast<int>(setting(config, "reset.utc.offset.minutes", 0, -720, 840));
    }

    std::string requestReset(const std::string& email) {
        if (!users_.exists(email)) throw std::out_of_range("unknown user");
        long long bound = 1;
        for (int i = 0; i < codeLength_; i++) bound *= 10;
        std::string code = std::to_string(codes_(bound));
        if (static_cast<int>(code.size()) < codeLength_) code.insert(0, codeLength_ - code.size(), '0');
        std::lock_guard<std::mutex> lock(mu_);
        pending_[email] = Pending{code, clock_.nowMillis() + ttlMillis_, 0};
        return code;
    }

    int64_t expiresAt(const std::string& email) {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = pending_.find(email);
        if (it == pending_.end()) throw std::out_of_range("no pending reset");
        return it->second.expiresAt;
    }

    std::string describeExpiry(const std::string& email) {
        using namespace std::chrono;
        sys_time<milliseconds> tp{milliseconds{expiresAt(email) + int64_t{offset_} * 60'000}};
        auto day = floor<days>(tp);
        year_month_day ymd{day};
        hh_mm_ss<minutes> hms{floor<minutes>(tp - day)};
        char buf[64];
        std::snprintf(buf, sizeof buf, "%04d-%02u-%02u %02d:%02d ", int(ymd.year()), unsigned(ymd.month()),
                      unsigned(ymd.day()), int(hms.hours().count()), int(hms.minutes().count()));
        return buf + label(offset_);
    }

    bool confirmReset(const std::string& email, const std::string& code, const std::string& newPassword) {
        if (newPassword.size() < 8) throw std::invalid_argument("password too short");
        {
            std::lock_guard<std::mutex> lock(mu_);
            auto it = pending_.find(email);
            if (it == pending_.end()) return false;
            if (clock_.nowMillis() >= it->second.expiresAt) {
                pending_.erase(it);
                return false;
            }
            if (!constantTimeEquals(it->second.code, code)) {
                if (++it->second.failures >= kMaxAttempts) pending_.erase(it);
                return false;
            }
            pending_.erase(it);  // consumed
        }
        users_.setPasswordHash(email, hashPassword(newPassword));
        return true;
    }

private:
    static constexpr int kMaxAttempts = 5;

    struct Pending {
        std::string code;
        int64_t expiresAt;
        int failures;
    };

    static long long setting(std::map<std::string, std::string>& config, const std::string& key, long long def,
                             long long lo, long long hi) {
        auto it = config.find(key);
        if (it == config.end()) return def;
        const std::string& raw = it->second;
        char* end = nullptr;
        long long v = std::strtoll(raw.c_str(), &end, 10);
        if (raw.empty() || *end != '\0' || v < lo || v > hi) {
            throw std::invalid_argument(key + " must be a number in " + std::to_string(lo) + ".." + std::to_string(hi));
        }
        return v;
    }

    static bool constantTimeEquals(const std::string& a, const std::string& b) {
        if (a.size() != b.size()) return false;
        unsigned char diff = 0;
        for (size_t i = 0; i < a.size(); i++) diff |= static_cast<unsigned char>(a[i] ^ b[i]);
        return diff == 0;
    }

    static std::string label(int offset) {
        if (offset == 0) return "UTC";
        char buf[16];
        int a = offset < 0 ? -offset : offset;
        std::snprintf(buf, sizeof buf, "UTC%c%02d:%02d", offset < 0 ? '-' : '+', a / 60, a % 60);
        return buf;
    }

    const Clock& clock_;
    UserStore& users_;
    std::function<long long(long long)> codes_;
    int64_t ttlMillis_;
    int codeLength_;
    int offset_;
    std::mutex mu_;
    std::map<std::string, Pending> pending_;
};
