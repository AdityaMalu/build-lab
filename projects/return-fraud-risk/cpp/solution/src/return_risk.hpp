#pragma once

#include <algorithm>

#include "return_risk_models.hpp"

// Fixes: exclusive late tiers with '>' (30 days is not late); $500 tier uses '>='; opened AND electronics;
// HIGH starts at 60; score capped at 100; validates returns and category; batch isolates bad rows.
class ReturnRiskEngine {
public:
    RiskResult assess(const ReturnRequest& r) const {
        if (r.orderId.find_first_not_of(" \t") == std::string::npos) throw std::invalid_argument("orderId required");
        if (r.amountCents < 0) throw std::invalid_argument("negative amount");
        if (r.daysSincePurchase < 0) throw std::invalid_argument("negative days");
        if (r.returnsLast90Days < 0) throw std::invalid_argument("negative return count");
        if (!r.category) throw std::invalid_argument("category required");

        int score = 0;
        std::vector<std::string> reasons;

        if (r.daysSincePurchase > 60) {
            score += 40;
            reasons.push_back("LATE_RETURN");
        } else if (r.daysSincePurchase > 30) {
            score += 25;
            reasons.push_back("LATE_RETURN");
        }

        if (r.amountCents >= 100'000) {
            score += 35;
            reasons.push_back("HIGH_VALUE");
        } else if (r.amountCents >= 50'000) {
            score += 20;
            reasons.push_back("HIGH_VALUE");
        }

        if (r.returnsLast90Days >= 6) {
            score += 30;
            reasons.push_back("FREQUENT_RETURNER");
        } else if (r.returnsLast90Days >= 3) {
            score += 15;
            reasons.push_back("FREQUENT_RETURNER");
        }

        if (r.opened && *r.category == Category::Electronics) {
            score += 10;
            reasons.push_back("OPENED_ELECTRONICS");
        }

        if (!r.hasReceipt) {
            score += 20;
            reasons.push_back("NO_RECEIPT");
        }

        score = std::min(score, 100);
        RiskLevel level = score >= 60 ? RiskLevel::High : score >= 30 ? RiskLevel::Medium : RiskLevel::Low;
        return RiskResult{score, level, reasons};
    }

    std::vector<BatchEntry> assessBatch(const std::vector<std::optional<ReturnRequest>>& requests) const {
        std::vector<BatchEntry> out;
        out.reserve(requests.size());
        for (const auto& r : requests) {
            if (!r) {
                out.push_back(BatchEntry{std::nullopt, std::nullopt, "request required"});
                continue;
            }
            try {
                out.push_back(BatchEntry{r->orderId, assess(*r), ""});
            } catch (const std::invalid_argument& e) {
                out.push_back(BatchEntry{r->orderId, std::nullopt, e.what()});
            }
        }
        return out;
    }
};
