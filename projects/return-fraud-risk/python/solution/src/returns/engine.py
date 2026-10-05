from .models import BatchEntry, Category, RiskLevel, RiskResult


class ReturnRiskEngine:
    """Fixes: exclusive late tiers with '>' (30 days is not late); $500 tier uses '>='; opened AND electronics;
    HIGH starts at 60; score capped at 100; validates returns and category; batch isolates bad rows;
    reasons is an immutable tuple."""

    def assess(self, r):
        if r is None:
            raise ValueError("request required")
        if not r.order_id or not str(r.order_id).strip():
            raise ValueError("order_id required")
        if r.amount_cents < 0:
            raise ValueError("negative amount")
        if r.days_since_purchase < 0:
            raise ValueError("negative days")
        if r.returns_last_90_days < 0:
            raise ValueError("negative return count")
        if not isinstance(r.category, Category):
            raise ValueError("category required")

        score = 0
        reasons = []

        if r.days_since_purchase > 60:
            score += 40
            reasons.append("LATE_RETURN")
        elif r.days_since_purchase > 30:
            score += 25
            reasons.append("LATE_RETURN")

        if r.amount_cents >= 100_000:
            score += 35
            reasons.append("HIGH_VALUE")
        elif r.amount_cents >= 50_000:
            score += 20
            reasons.append("HIGH_VALUE")

        if r.returns_last_90_days >= 6:
            score += 30
            reasons.append("FREQUENT_RETURNER")
        elif r.returns_last_90_days >= 3:
            score += 15
            reasons.append("FREQUENT_RETURNER")

        if r.opened and r.category == Category.ELECTRONICS:
            score += 10
            reasons.append("OPENED_ELECTRONICS")

        if not r.has_receipt:
            score += 20
            reasons.append("NO_RECEIPT")

        score = min(score, 100)
        level = RiskLevel.HIGH if score >= 60 else RiskLevel.MEDIUM if score >= 30 else RiskLevel.LOW
        return RiskResult(score, level, tuple(reasons))

    def assess_batch(self, requests):
        if requests is None:
            raise ValueError("requests required")
        out = []
        for r in requests:
            order_id = None if r is None else r.order_id
            try:
                out.append(BatchEntry(order_id, self.assess(r), None))
            except ValueError as e:
                out.append(BatchEntry(order_id, None, str(e) or "invalid request"))
        return out
