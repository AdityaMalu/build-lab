package returns;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixes: late-return tiers are exclusive and use '>' (30 days is not late); $500 tier uses '>=';
 * opened AND electronics (was OR); HIGH starts at 60; score capped at 100; validation of
 * returns/category; batch isolates bad rows; reasons list is immutable.
 */
public class ReturnRiskEngine {

    public RiskResult assess(ReturnRequest r) {
        if (r == null) throw new IllegalArgumentException("request required");
        if (r.orderId() == null || r.orderId().isBlank()) throw new IllegalArgumentException("orderId required");
        if (r.amountCents() < 0) throw new IllegalArgumentException("negative amount");
        if (r.daysSincePurchase() < 0) throw new IllegalArgumentException("negative days");
        if (r.returnsLast90Days() < 0) throw new IllegalArgumentException("negative return count");
        if (r.category() == null) throw new IllegalArgumentException("category required");

        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (r.daysSincePurchase() > 60) {
            score += 40;
            reasons.add("LATE_RETURN");
        } else if (r.daysSincePurchase() > 30) {
            score += 25;
            reasons.add("LATE_RETURN");
        }

        if (r.amountCents() >= 100_000) {
            score += 35;
            reasons.add("HIGH_VALUE");
        } else if (r.amountCents() >= 50_000) {
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

        if (r.opened() && r.category() == Category.ELECTRONICS) {
            score += 10;
            reasons.add("OPENED_ELECTRONICS");
        }

        if (!r.hasReceipt()) {
            score += 20;
            reasons.add("NO_RECEIPT");
        }

        score = Math.min(score, 100);
        RiskLevel level = score >= 60 ? RiskLevel.HIGH : score >= 30 ? RiskLevel.MEDIUM : RiskLevel.LOW;
        return new RiskResult(score, level, List.copyOf(reasons));
    }

    public List<BatchEntry> assessBatch(List<ReturnRequest> requests) {
        if (requests == null) throw new IllegalArgumentException("requests required");
        List<BatchEntry> out = new ArrayList<>(requests.size());
        for (ReturnRequest r : requests) {
            String orderId = r == null ? null : r.orderId();
            try {
                out.add(BatchEntry.ok(orderId, assess(r)));
            } catch (IllegalArgumentException e) {
                out.add(BatchEntry.error(orderId, e.getMessage()));
            }
        }
        return out;
    }
}
