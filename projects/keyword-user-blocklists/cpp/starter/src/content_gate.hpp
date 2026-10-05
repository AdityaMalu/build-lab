#pragma once

#include "content_gate_models.hpp"

class ContentGate {
public:
    explicit ContentGate(const Clock& clock) : clock_(clock) {}

    void addKeyword(const std::string& phrase) {
        (void)phrase;
        throw std::logic_error("TODO");
    }

    void removeKeyword(const std::string& phrase) {
        (void)phrase;
        throw std::logic_error("TODO");
    }

    void blockUser(const std::string& userId, int64_t durationMillis) {
        (void)userId;
        (void)durationMillis;
        throw std::logic_error("TODO");
    }

    void unblockUser(const std::string& userId) {
        (void)userId;
        throw std::logic_error("TODO");
    }

    bool isBlocked(const std::string& userId) {
        (void)userId;
        throw std::logic_error("TODO");
    }

    Decision submit(const std::string& userId, const std::string& text) {
        (void)userId;
        (void)text;
        throw std::logic_error("TODO");
    }

    int activeStrikes(const std::string& userId) {
        (void)userId;
        throw std::logic_error("TODO");
    }

private:
    const Clock& clock_;
    // TODO: keywords and per-user state
};
