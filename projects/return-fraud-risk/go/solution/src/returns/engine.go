package returns

import (
	"fmt"
	"strings"
)

// Assess scores one return request.
//
// Fixes: exclusive late tiers with '>' (30 days is not late); $500 tier uses '>='; opened AND electronics;
// HIGH starts at 60; score capped at 100; validates returns and category; batch isolates bad rows.
func Assess(r *ReturnRequest) (RiskResult, error) {
	switch {
	case r == nil:
		return RiskResult{}, fmt.Errorf("request required: %w", ErrInvalidRequest)
	case strings.TrimSpace(r.OrderID) == "":
		return RiskResult{}, fmt.Errorf("order id required: %w", ErrInvalidRequest)
	case r.AmountCents < 0:
		return RiskResult{}, fmt.Errorf("negative amount: %w", ErrInvalidRequest)
	case r.DaysSincePurchase < 0:
		return RiskResult{}, fmt.Errorf("negative days: %w", ErrInvalidRequest)
	case r.ReturnsLast90Days < 0:
		return RiskResult{}, fmt.Errorf("negative return count: %w", ErrInvalidRequest)
	case r.Category < Electronics || r.Category > Toys:
		return RiskResult{}, fmt.Errorf("category required: %w", ErrInvalidRequest)
	}

	score := 0
	reasons := []string{}

	switch {
	case r.DaysSincePurchase > 60:
		score += 40
		reasons = append(reasons, "LATE_RETURN")
	case r.DaysSincePurchase > 30:
		score += 25
		reasons = append(reasons, "LATE_RETURN")
	}

	switch {
	case r.AmountCents >= 100_000:
		score += 35
		reasons = append(reasons, "HIGH_VALUE")
	case r.AmountCents >= 50_000:
		score += 20
		reasons = append(reasons, "HIGH_VALUE")
	}

	switch {
	case r.ReturnsLast90Days >= 6:
		score += 30
		reasons = append(reasons, "FREQUENT_RETURNER")
	case r.ReturnsLast90Days >= 3:
		score += 15
		reasons = append(reasons, "FREQUENT_RETURNER")
	}

	if r.Opened && r.Category == Electronics {
		score += 10
		reasons = append(reasons, "OPENED_ELECTRONICS")
	}

	if !r.HasReceipt {
		score += 20
		reasons = append(reasons, "NO_RECEIPT")
	}

	if score > 100 {
		score = 100
	}
	level := Low
	switch {
	case score >= 60:
		level = High
	case score >= 30:
		level = Medium
	}
	return RiskResult{Score: score, Level: level, Reasons: reasons}, nil
}

// AssessBatch scores every request; a bad row becomes an error entry and doesn't stop the batch.
func AssessBatch(requests []*ReturnRequest) []BatchEntry {
	out := make([]BatchEntry, 0, len(requests))
	for _, r := range requests {
		id := ""
		if r != nil {
			id = r.OrderID
		}
		res, err := Assess(r)
		if err != nil {
			out = append(out, BatchEntry{OrderID: id, Err: err})
			continue
		}
		out = append(out, BatchEntry{OrderID: id, Result: &res})
	}
	return out
}
