#pragma once

#include <cstdint>
#include <optional>
#include <stdexcept>
#include <string>

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
    (void)n;
    throw std::logic_error("TODO");
}

inline long long decode(const std::string& s) {
    (void)s;
    throw std::logic_error("TODO");
}

}  // namespace base62

class UrlShortener {
public:
    explicit UrlShortener(const Clock& clock) : clock_(clock) {}

    std::string shorten(const std::string& url, int64_t ttlMillis) {
        (void)url;
        (void)ttlMillis;
        throw std::logic_error("TODO");
    }

    std::string shortenWithAlias(const std::string& url, const std::string& alias, int64_t ttlMillis) {
        (void)url;
        (void)alias;
        (void)ttlMillis;
        throw std::logic_error("TODO");
    }

    std::optional<std::string> resolve(const std::string& code) {
        (void)code;
        throw std::logic_error("TODO");
    }

    LinkStats stats(const std::string& code) {
        (void)code;
        throw std::logic_error("TODO");
    }

private:
    const Clock& clock_;
    // TODO: storage
};
