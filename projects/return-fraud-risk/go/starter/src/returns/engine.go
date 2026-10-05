package returns

import (
	"fmt"
	"strings"
)

// Assess scores one return request.
func Assess(r *ReturnRequest) (RiskResult, error) {
	if r == nil {
		return RiskResult{}, fmt.Errorf("request required: %w", ErrInvalidRequest)
	}
	if strings.TrimSpace(r.OrderID) == "" {
		return RiskResult{}, fmt.Errorf("order id required: %w", ErrInvalidRequest)
	}
	if r.AmountCents < 0 {
		return RiskResult{}, fmt.Errorf("negative amount: %w", ErrInvalidRequest)
	}
	if r.DaysSincePurchase < 0 {
		return RiskResult{}, fmt.Errorf("negative days: %w", ErrInvalidRequest)
	}

	score := 0
	var reasons []string

	if r.DaysSincePurchase >= 30 {
		score += 25
		reasons = append(reasons, "LATE_RETURN")
	}
	if r.DaysSincePurchase > 60 {
		score += 40
	}

	if r.AmountCents >= 100_000 {
		score += 35
		reasons = append(reasons, "HIGH_VALUE")
	} else if r.AmountCents > 50_000 {
		score += 20
		reasons = append(reasons, "HIGH_VALUE")
	}

	if r.ReturnsLast90Days >= 6 {
		score += 30
		reasons = append(reasons, "FREQUENT_RETURNER")
	} else if r.ReturnsLast90Days >= 3 {
		score += 15
		reasons = append(reasons, "FREQUENT_RETURNER")
	}

	if r.Opened || r.Category == Electronics {
		score += 10
		reasons = append(reasons, "OPENED_ELECTRONICS")
	}

	if !r.HasReceipt {
		score += 20
		reasons = append(reasons, "NO_RECEIPT")
	}

	level := Low
	if score > 60 {
		level = High
	} else if score >= 30 {
		level = Medium
	}
	return RiskResult{Score: score, Level: level, Reasons: reasons}, nil
}

// AssessBatch scores every request.
func AssessBatch(requests []*ReturnRequest) []BatchEntry {
	var out []BatchEntry
	for _, r := range requests {
		res, err := Assess(r)
		if err != nil {
			return out
		}
		out = append(out, BatchEntry{OrderID: r.OrderID, Result: &res})
	}
	return out
}
