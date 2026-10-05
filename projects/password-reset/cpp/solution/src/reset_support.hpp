#pragma once
// Given: clock, user store and the (demo-grade) password hash.

#include <cstdint>
#include <cstdio>
#include <map>
#include <mutex>
#include <stdexcept>
#include <string>

struct Clock {
    virtual ~Clock() = default;
    virtual int64_t nowMillis() const = 0;
};

struct UserStore {
    virtual ~UserStore() = default;
    virtual bool exists(const std::string& email) = 0;
    virtual void setPasswordHash(const std::string& email, const std::string& hash) = 0;
    virtual std::string passwordHash(const std::string& email) = 0;
};

/** Demo only: 64-bit FNV-1a of a salted password. Use a slow, salted KDF (bcrypt/Argon2) in real code. */
inline std::string hashPassword(const std::string& password) {
    uint64_t h = 1469598103934665603ULL;
    for (unsigned char c : "practice-lab:" + password) {
        h ^= c;
        h *= 1099511628211ULL;
    }
    char buf[32];
    std::snprintf(buf, sizeof buf, "fnv1a64:%016llx", static_cast<unsigned long long>(h));
    return buf;
}

class InMemoryUserStore : public UserStore {
public:
    InMemoryUserStore& add(const std::string& email, const std::string& hash) {
        std::lock_guard<std::mutex> lock(mu_);
        hashes_[email] = hash;
        return *this;
    }
    bool exists(const std::string& email) override {
        std::lock_guard<std::mutex> lock(mu_);
        return hashes_.count(email) > 0;
    }
    void setPasswordHash(const std::string& email, const std::string& hash) override {
        std::lock_guard<std::mutex> lock(mu_);
        if (!hashes_.count(email)) throw std::out_of_range("no user " + email);
        hashes_[email] = hash;
    }
    std::string passwordHash(const std::string& email) override {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = hashes_.find(email);
        return it == hashes_.end() ? "" : it->second;
    }

private:
    std::mutex mu_;
    std::map<std::string, std::string> hashes_;
};
