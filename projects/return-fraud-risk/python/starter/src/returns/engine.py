from .models import BatchEntry, Category, RiskLevel, RiskResult


class ReturnRiskEngine:

    def assess(self, r):
        if r is None:
            raise ValueError("request required")
        if not r.order_id or not r.order_id.strip():
            raise ValueError("order_id required")
        if r.amount_cents < 0:
            raise ValueError("negative amount")
        if r.days_since_purchase < 0:
            raise ValueError("negative days")

        score = 0
        reasons = []

        if r.days_since_purchase >= 30:
            score += 25
            reasons.append("LATE_RETURN")
        if r.days_since_purchase > 60:
            score += 40

        if r.amount_cents >= 100_000:
            score += 35
            reasons.append("HIGH_VALUE")
        elif r.amount_cents > 50_000:
            score += 20
            reasons.append("HIGH_VALUE")

        if r.returns_last_90_days >= 6:
            score += 30
            reasons.append("FREQUENT_RETURNER")
        elif r.returns_last_90_days >= 3:
            score += 15
            reasons.append("FREQUENT_RETURNER")

        if r.opened or r.category == Category.ELECTRONICS:
            score += 10
            reasons.append("OPENED_ELECTRONICS")

        if not r.has_receipt:
            score += 20
            reasons.append("NO_RECEIPT")

        level = RiskLevel.HIGH if score > 60 else RiskLevel.MEDIUM if score >= 30 else RiskLevel.LOW
        return RiskResult(score, level, reasons)

    def assess_batch(self, requests):
        return [BatchEntry(r.order_id, self.assess(r), None) for r in requests]
