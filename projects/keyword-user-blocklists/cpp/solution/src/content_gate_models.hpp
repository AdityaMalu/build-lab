#pragma once

#include <cstdint>
#include <ostream>
#include <stdexcept>
#include <string>

struct Clock {
    virtual ~Clock() = default;
    virtual int64_t nowMillis() const = 0;
};

enum class Decision { Accepted, RejectedKeyword, BlockedUser };

inline std::ostream& operator<<(std::ostream& os, Decision d) {
    switch (d) {
        case Decision::Accepted: return os << "ACCEPTED";
        case Decision::RejectedKeyword: return os << "REJECTED_KEYWORD";
        case Decision::BlockedUser: return os << "BLOCKED_USER";
    }
    return os << "?";
}

constexpr int kStrikeLimit = 3;
constexpr int64_t kStrikeWindowMillis = 60LL * 60 * 1000;
constexpr int64_t kAutoBlockMillis = 24LL * 60 * 60 * 1000;
