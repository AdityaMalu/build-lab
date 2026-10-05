#pragma once

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <limits>
#include <mutex>
#include <optional>
#include <regex>
#include <stdexcept>
#include <string>
#include <unordered_map>

struct Clock {
    virtual ~Clock() = default;
    virtual int64_t nowMillis() const = 0;
};

struct LinkStats {
    std::string code;
    long long hits;
    int64_t lastAccessMillis;
};

struct AliasTaken : std::runtime_error {
    explicit AliasTaken(const std::string& alias) : std::runtime_error("alias taken: " + alias) {}
};

namespace base62 {

inline const std::string ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

inline std::string encode(long long n) {
    if (n < 0) throw std::invalid_argument("negative");
    if (n == 0) return "0";
    std::string out;
    while (n > 0) {
        out.push_back(ALPHABET[static_cast<size_t>(n % 62)]);
        n /= 62;
    }
    std::reverse(out.begin(), out.end());
    return out;
}

inline long long decode(const std::string& s) {
    if (s.empty()) throw std::invalid_argument("empty");
    long long n = 0;
    for (char c : s) {
        auto d = ALPHABET.find(c);
        if (d == std::string::npos) throw std::invalid_argument(std::string("invalid character ") + c);
        if (n > (std::numeric_limits<long long>::max() - static_cast<long long>(d)) / 62)
            throw std::invalid_argument("overflow");
        n = n * 62 + static_cast<long long>(d);
    }
    return n;
}

}  // namespace base62

class UrlShortener {
public:
    explicit UrlShortener(const Clock& clock) : clock_(clock) {}

    std::string shorten(const std::string& url, int64_t ttlMillis) {
        validateUrl(url);
        std::lock_guard<std::mutex> lock(mu_);
        int64_t now = clock_.nowMillis();
        auto existing = generated_.find(url);
        if (existing != generated_.end() && links_.at(existing->second).expiresAt > now) return existing->second;
        std::string code;
        do {
            code = base62::encode(kOffset + counter_++);
        } while (links_.count(code));  // an alias may have taken it
        links_[code] = Link{url, expiry(now, ttlMillis), 0, -1};
        generated_[url] = code;
        return code;
    }

    std::string shortenWithAlias(const std::string& url, const std::string& alias, int64_t ttlMillis) {
        validateUrl(url);
        static const std::regex pattern("[A-Za-z0-9_-]{3,32}");
        if (!std::regex_match(alias, pattern)) throw std::invalid_argument("bad alias");
        std::lock_guard<std::mutex> lock(mu_);
        if (links_.count(alias)) throw AliasTaken(alias);
        links_[alias] = Link{url, expiry(clock_.nowMillis(), ttlMillis), 0, -1};
        return alias;
    }

    std::optional<std::string> resolve(const std::string& code) {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = links_.find(code);
        int64_t now = clock_.nowMillis();
        if (it == links_.end() || now >= it->second.expiresAt) return std::nullopt;
        it->second.hits++;
        it->second.lastAccess = now;
        return it->second.url;
    }

    LinkStats stats(const std::string& code) {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = links_.find(code);
        if (it == links_.end()) throw std::out_of_range("unknown code " + code);
        return LinkStats{code, it->second.hits, it->second.lastAccess};
    }

private:
    struct Link {
        std::string url;
        int64_t expiresAt;
        long long hits;
        int64_t lastAccess;
    };

    static constexpr long long kOffset = 916'132'832;  // 62^5: at least 6 digits

    static int64_t expiry(int64_t now, int64_t ttl) {
        if (ttl <= 0 || now > std::numeric_limits<int64_t>::max() - ttl) return std::numeric_limits<int64_t>::max();
        return now + ttl;
    }

    static void validateUrl(const std::string& url) {
        if (url.size() > 2048) throw std::invalid_argument("url too long");
        std::string rest;
        if (url.rfind("https://", 0) == 0) rest = url.substr(8);
        else if (url.rfind("http://", 0) == 0) rest = url.substr(7);
        else throw std::invalid_argument("url must be http(s)");
        if (rest.empty()) throw std::invalid_argument("missing host");
        for (unsigned char c : url)
            if (std::isspace(c)) throw std::invalid_argument("whitespace in url");
    }

    const Clock& clock_;
    std::mutex mu_;
    std::unordered_map<std::string, Link> links_;
    std::unordered_map<std::string, std::string> generated_;
    long long counter_ = 0;
};
