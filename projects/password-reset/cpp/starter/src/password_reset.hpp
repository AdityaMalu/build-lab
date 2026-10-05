#pragma once

#include <chrono>
#include <functional>
#include <map>

#include "reset_support.hpp"

class PasswordResetService {
public:
    PasswordResetService(std::map<std::string, std::string> config, const Clock& clock, UserStore& users,
                         std::function<long long(long long)> codes)
        : clock_(clock), users_(users), codes_(std::move(codes)) {
        ttlMinutes_ = config.count("reset.ttl.minute") ? std::stoll(config["reset.ttl.minute"]) : 15;
        codeLength_ = config.count("reset.code.length") ? std::stoi(config["reset.code.length"]) : 6;
        offset_ = config.count("reset.utc.offset.minutes") ? std::stoi(config["reset.utc.offset.minutes"]) : 0;
    }

    std::string requestReset(const std::string& email) {
        if (!users_.exists(email)) throw std::out_of_range("unknown user");
        long long bound = 1;
        for (int i = 0; i < codeLength_; i++) bound *= 10;
        std::string code = std::to_string(codes_(bound));
        auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
                       std::chrono::system_clock::now().time_since_epoch()).count();
        pending_[email] = Pending{code, now + ttlMinutes_ * 60'000};
        return code;
    }

    int64_t expiresAt(const std::string& email) {
        auto it = pending_.find(email);
        if (it == pending_.end()) throw std::out_of_range("no pending reset");
        return it->second.expiresAt;
    }

    std::string describeExpiry(const std::string& email) {
        using namespace std::chrono;
        sys_time<milliseconds> tp{milliseconds{expiresAt(email)}};
        auto day = floor<days>(tp);
        year_month_day ymd{day};
        hh_mm_ss<minutes> hms{floor<minutes>(tp - day)};
        char buf[64];
        std::snprintf(buf, sizeof buf, "%04d-%02u-%02u %02d:%02d UTC", int(ymd.year()), unsigned(ymd.month()),
                      unsigned(ymd.day()), int(hms.hours().count()), int(hms.minutes().count()));
        return buf;
    }

    bool confirmReset(const std::string& email, const std::string& code, const std::string& newPassword) {
        auto it = pending_.find(email);
        if (it == pending_.end()) return false;
        if (clock_.nowMillis() > it->second.expiresAt) return false;
        if (it->second.code != code) return false;
        if (newPassword.size() < 8) throw std::invalid_argument("password too short");
        users_.setPasswordHash(email, newPassword);
        return true;
    }

private:
    struct Pending {
        std::string code;
        int64_t expiresAt;
    };

    const Clock& clock_;
    UserStore& users_;
    std::function<long long(long long)> codes_;
    long long ttlMinutes_;
    int codeLength_;
    int offset_;
    std::map<std::string, Pending> pending_;
};
