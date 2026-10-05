package returns

import "errors"

// Category of the returned item. The zero value is not a valid category.
type Category int

const (
	Electronics Category = iota + 1
	Apparel
	Home
	Books
	Toys
)

// RiskLevel of a return.
type RiskLevel string

const (
	Low    RiskLevel = "LOW"
	Medium RiskLevel = "MEDIUM"
	High   RiskLevel = "HIGH"
)

// ReturnRequest is one return to score.
type ReturnRequest struct {
	OrderID           string
	AmountCents       int64
	DaysSincePurchase int
	ReturnsLast90Days int
	Category          Category
	Opened            bool
	HasReceipt        bool
}

// RiskResult is the score, level and the rule codes that fired.
type RiskResult struct {
	Score   int
	Level   RiskLevel
	Reasons []string
}

// BatchEntry is one row of a batch: a result, or an error for a bad row.
type BatchEntry struct {
	OrderID string
	Result  *RiskResult
	Err     error
}

// ErrInvalidRequest is wrapped by every validation error.
var ErrInvalidRequest = errors.New("invalid request")
