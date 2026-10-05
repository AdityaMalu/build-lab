#pragma once

#include <optional>
#include <ostream>
#include <stdexcept>
#include <string>
#include <vector>

enum class Category { Electronics, Apparel, Home, Books, Toys };
enum class RiskLevel { Low, Medium, High };

inline std::ostream& operator<<(std::ostream& os, RiskLevel level) {
    switch (level) {
        case RiskLevel::Low: return os << "LOW";
        case RiskLevel::Medium: return os << "MEDIUM";
        case RiskLevel::High: return os << "HIGH";
    }
    return os << "?";
}

struct ReturnRequest {
    std::string orderId;
    long long amountCents = 0;
    int daysSincePurchase = 0;
    int returnsLast90Days = 0;
    std::optional<Category> category;
    bool opened = false;
    bool hasReceipt = true;
};

struct RiskResult {
    int score = 0;
    RiskLevel level = RiskLevel::Low;
    std::vector<std::string> reasons;
};

struct BatchEntry {
    std::optional<std::string> orderId;
    std::optional<RiskResult> result;
    std::string error;
    bool ok() const { return result.has_value(); }
};
