#pragma once

#include "return_risk_models.hpp"

class ReturnRiskEngine {
public:
    RiskResult assess(const ReturnRequest& r) const {
        if (r.orderId.find_first_not_of(' ') == std::string::npos) throw std::invalid_argument("orderId required");
        if (r.amountCents < 0) throw std::invalid_argument("negative amount");
        if (r.daysSincePurchase < 0) throw std::invalid_argument("negative days");

        int score = 0;
        std::vector<std::string> reasons;

        if (r.daysSincePurchase >= 30) {
            score += 25;
            reasons.push_back("LATE_RETURN");
        }
        if (r.daysSincePurchase > 60) {
            score += 40;
        }

        if (r.amountCents >= 100'000) {
            score += 35;
            reasons.push_back("HIGH_VALUE");
        } else if (r.amountCents > 50'000) {
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

        if (r.opened || r.category == Category::Electronics) {
            score += 10;
            reasons.push_back("OPENED_ELECTRONICS");
        }

        if (!r.hasReceipt) {
            score += 20;
            reasons.push_back("NO_RECEIPT");
        }

        RiskLevel level = score > 60 ? RiskLevel::High : score >= 30 ? RiskLevel::Medium : RiskLevel::Low;
        return RiskResult{score, level, reasons};
    }

    std::vector<BatchEntry> assessBatch(const std::vector<std::optional<ReturnRequest>>& requests) const {
        std::vector<BatchEntry> out;
        for (const auto& r : requests) {
            out.push_back(BatchEntry{r.value().orderId, assess(r.value()), ""});
        }
        return out;
    }
};
