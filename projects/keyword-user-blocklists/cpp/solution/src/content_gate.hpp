#pragma once

#include <cctype>
#include <deque>
#include <limits>
#include <memory>
#include <mutex>
#include <set>
#include <shared_mutex>
#include <unordered_map>
#include <vector>

#include "content_gate_models.hpp"

class ContentGate {
public:
    explicit ContentGate(const Clock& clock) : clock_(clock) {}

    void addKeyword(const std::string& phrase) {
        std::string k = normalize(phrase);
        std::unique_lock lock(kwMu_);
        keywords_.insert(k);
    }

    void removeKeyword(const std::string& phrase) {
        std::string k = normalize(phrase);
        std::unique_lock lock(kwMu_);
        keywords_.erase(k);
    }

    void blockUser(const std::string& userId, int64_t durationMillis) {
        UserState& s = state(userId);
        std::lock_guard<std::mutex> lock(s.mu);
        s.blockedUntil = durationMillis <= 0 ? kForever : clock_.nowMillis() + durationMillis;
    }

    void unblockUser(const std::string& userId) {
        UserState& s = state(userId);
        std::lock_guard<std::mutex> lock(s.mu);
        s.blockedUntil = kNever;
        s.strikes.clear();
    }

    bool isBlocked(const std::string& userId) {
        UserState& s = state(userId);
        std::lock_guard<std::mutex> lock(s.mu);
        return clock_.nowMillis() < s.blockedUntil;
    }

    Decision submit(const std::string& userId, const std::string& text) {
        UserState& s = state(userId);
        bool violates = matches(tokens(text));  // pure: no user lock needed
        std::lock_guard<std::mutex> lock(s.mu);
        int64_t now = clock_.nowMillis();
        if (now < s.blockedUntil) return Decision::BlockedUser;
        if (!violates) return Decision::Accepted;
        prune(s, now);
        s.strikes.push_back(now);
        if (static_cast<int>(s.strikes.size()) >= kStrikeLimit) {
            s.blockedUntil = now + kAutoBlockMillis;
            s.strikes.clear();
        }
        return Decision::RejectedKeyword;
    }

    int activeStrikes(const std::string& userId) {
        UserState& s = state(userId);
        std::lock_guard<std::mutex> lock(s.mu);
        prune(s, clock_.nowMillis());
        return static_cast<int>(s.strikes.size());
    }

private:
    static constexpr int64_t kNever = std::numeric_limits<int64_t>::min();
    static constexpr int64_t kForever = std::numeric_limits<int64_t>::max();

    struct UserState {
        std::mutex mu;
        int64_t blockedUntil = kNever;
        std::deque<int64_t> strikes;
    };

    static std::vector<std::string> tokens(const std::string& text) {
        std::vector<std::string> out;
        std::string cur;
        for (unsigned char c : text) {
            if (std::isalnum(c)) {
                cur.push_back(static_cast<char>(std::tolower(c)));
            } else if (!cur.empty()) {
                out.push_back(cur);
                cur.clear();
            }
        }
        if (!cur.empty()) out.push_back(cur);
        return out;
    }

    static std::string join(const std::vector<std::string>& t) {
        std::string s;
        for (size_t i = 0; i < t.size(); i++) s += (i ? " " : "") + t[i];
        return s;
    }

    static std::string normalize(const std::string& phrase) {
        auto t = tokens(phrase);
        if (t.empty()) throw std::invalid_argument("phrase has no words");
        return join(t);
    }

    bool matches(const std::vector<std::string>& textTokens) {
        if (textTokens.empty()) return false;
        std::string joined = " " + join(textTokens) + " ";
        std::shared_lock lock(kwMu_);
        for (const auto& k : keywords_)
            if (joined.find(" " + k + " ") != std::string::npos) return true;
        return false;
    }

    static void prune(UserState& s, int64_t now) {
        while (!s.strikes.empty() && s.strikes.front() + kStrikeWindowMillis <= now) s.strikes.pop_front();
    }

    UserState& state(const std::string& userId) {
        if (userId.find_first_not_of(" \t") == std::string::npos) throw std::invalid_argument("userId required");
        std::lock_guard<std::mutex> lock(usersMu_);
        auto& slot = users_[userId];
        if (!slot) slot = std::make_unique<UserState>();
        return *slot;
    }

    const Clock& clock_;
    std::shared_mutex kwMu_;
    std::set<std::string> keywords_;
    std::mutex usersMu_;
    std::unordered_map<std::string, std::unique_ptr<UserState>> users_;
};
