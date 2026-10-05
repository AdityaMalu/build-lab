#pragma once

#include "flags_models.hpp"

/** 32-bit FNV-1a of key, modulo buckets. */
inline uint32_t bucket(const std::string& key, uint32_t buckets) {
    (void)key;
    (void)buckets;
    throw std::logic_error("TODO");
}

class FlagService {
public:
    void define(const Flag& f) {
        (void)f;
        throw std::logic_error("TODO");
    }

    std::string evaluate(const std::string& flagKey, const User& u) {
        (void)flagKey;
        (void)u;
        throw std::logic_error("TODO");
    }

private:
    // TODO: storage
};
