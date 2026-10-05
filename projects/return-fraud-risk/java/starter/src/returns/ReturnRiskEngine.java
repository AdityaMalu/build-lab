package returns;

import java.util.ArrayList;
import java.util.List;

public class ReturnRiskEngine {

    public RiskResult assess(ReturnRequest r) {
        if (r == null) throw new IllegalArgumentException("request required");
        if (r.orderId() == null || r.orderId().isBlank()) throw new IllegalArgumentException("orderId required");
        if (r.amountCents() < 0) throw new IllegalArgumentException("negative amount");
        if (r.daysSincePurchase() < 0) throw new IllegalArgumentException("negative days");

        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (r.daysSincePurchase() >= 30) {
            score += 25;
            reasons.add("LATE_RETURN");
        }
        if (r.daysSincePurchase() > 60) {
            score += 40;
        }

        if (r.amountCents() >= 100_000) {
            score += 35;
            reasons.add("HIGH_VALUE");
        } else if (r.amountCents() > 50_000) {
            score += 20;
            reasons.add("HIGH_VALUE");
        }

        if (r.returnsLast90Days() >= 6) {
            score += 30;
            reasons.add("FREQUENT_RETURNER");
        } else if (r.returnsLast90Days() >= 3) {
            score += 15;
            reasons.add("FREQUENT_RETURNER");
        }

        if (r.opened() || r.category() == Category.ELECTRONICS) {
            score += 10;
            reasons.add("OPENED_ELECTRONICS");
        }

        if (!r.hasReceipt()) {
            score += 20;
            reasons.add("NO_RECEIPT");
        }

        RiskLevel level = score > 60 ? RiskLevel.HIGH : score >= 30 ? RiskLevel.MEDIUM : RiskLevel.LOW;
        return new RiskResult(score, level, reasons);
    }

    public List<BatchEntry> assessBatch(List<ReturnRequest> requests) {
        List<BatchEntry> out = new ArrayList<>();
        for (ReturnRequest r : requests) {
            out.add(BatchEntry.ok(r.orderId(), assess(r)));
        }
        return out;
    }
}
