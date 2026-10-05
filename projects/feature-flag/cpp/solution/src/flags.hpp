#pragma once

#include <memory>
#include <mutex>
#include <unordered_map>

#include "flags_models.hpp"

/** 32-bit FNV-1a of key, modulo buckets. */
inline uint32_t bucket(const std::string& key, uint32_t buckets) {
    if (buckets == 0) throw std::invalid_argument("buckets must be positive");
    uint32_t h = 0x811C9DC5u;
    for (unsigned char b : key) {
        h ^= b;
        h *= 0x01000193u;
    }
    return h % buckets;
}

/** Flags are stored as immutable shared snapshots; evaluate copies the pointer and works lock-free. */
class FlagService {
public:
    void define(const Flag& f) {
        if (f.key.find_first_not_of(" \t") == std::string::npos) throw std::invalid_argument("key required");
        if (f.rolloutPercent < 0 || f.rolloutPercent > 100) throw std::invalid_argument("rollout must be 0..100");
        int sum = 0;
        for (const auto& v : f.variants) {
            if (v.weight < 0) throw std::invalid_argument("negative weight");
            sum += v.weight;
        }
        if (!f.variants.empty() && sum != 100) throw std::invalid_argument("weights must sum to 100");
        auto snapshot = std::make_shared<const Flag>(f);
        std::lock_guard<std::mutex> lock(mu_);
        flags_[f.key] = std::move(snapshot);
    }

    std::string evaluate(const std::string& flagKey, const User& u) {
        std::shared_ptr<const Flag> f;
        {
            std::lock_guard<std::mutex> lock(mu_);
            auto it = flags_.find(flagKey);
            if (it == flags_.end()) throw std::out_of_range("unknown flag " + flagKey);
            f = it->second;
        }
        if (u.id.find_first_not_of(" \t") == std::string::npos) throw std::invalid_argument("user id required");
        if (!f->enabled) return "off";
        for (const auto& r : f->rules) {
            auto it = u.attributes.find(r.attribute);
            if (it != u.attributes.end() && it->second == r.equalsValue) return r.variant;
        }
        if (static_cast<int>(bucket(flagKey + ":" + u.id, 100)) >= f->rolloutPercent) return "off";
        if (f->variants.empty()) return "on";
        int b = static_cast<int>(bucket(flagKey + ":variant:" + u.id, 100));
        int cumulative = 0;
        for (const auto& v : f->variants) {
            cumulative += v.weight;
            if (b < cumulative) return v.name;
        }
        throw std::runtime_error("weights did not cover bucket");
    }

private:
    std::mutex mu_;
    std::unordered_map<std::string, std::shared_ptr<const Flag>> flags_;
};
