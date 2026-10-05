package returns

import (
	"errors"
	"reflect"
	"testing"
)

func req() *ReturnRequest {
	return &ReturnRequest{OrderID: "o", AmountCents: 1_000, DaysSincePurchase: 5, Category: Books, HasReceipt: true}
}

func assess(t *testing.T, r *ReturnRequest) RiskResult {
	t.Helper()
	res, err := Assess(r)
	if err != nil {
		t.Fatalf("Assess(%+v) failed: %v", *r, err)
	}
	return res
}

func scoreWith(t *testing.T, change func(r *ReturnRequest)) int {
	t.Helper()
	r := req()
	change(r)
	return assess(t, r).Score
}

func expectScore(t *testing.T, want int, change func(r *ReturnRequest), why string) {
	t.Helper()
	if got := scoreWith(t, change); got != want {
		t.Fatalf("%s: want score %d, got %d", why, want, got)
	}
}

// Test: a clean request scores 0 / LOW with no reasons
func TestCleanRequest(t *testing.T) {
	res := assess(t, req())
	if res.Score != 0 || res.Level != Low || len(res.Reasons) != 0 {
		t.Fatalf("want 0/LOW/no reasons, got %+v", res)
	}
}

// Test: late-return boundaries: 30 / 31 / 60 / 61 days
func TestLateBoundaries(t *testing.T) {
	expectScore(t, 0, func(r *ReturnRequest) { r.DaysSincePurchase = 30 }, "30 days is not late")
	expectScore(t, 25, func(r *ReturnRequest) { r.DaysSincePurchase = 31 }, "31 days")
	expectScore(t, 25, func(r *ReturnRequest) { r.DaysSincePurchase = 60 }, "60 days is still the lower tier")
	expectScore(t, 40, func(r *ReturnRequest) { r.DaysSincePurchase = 61 }, "61 days: 40, not 25+40")
	r := req()
	r.DaysSincePurchase = 90
	if got := assess(t, r).Reasons; !reflect.DeepEqual(got, []string{"LATE_RETURN"}) {
		t.Fatalf("reasons: want [LATE_RETURN], got %v", got)
	}
}

// Test: amount boundaries: $499.99 / $500 / $999.99 / $1000
func TestAmountBoundaries(t *testing.T) {
	expectScore(t, 0, func(r *ReturnRequest) { r.AmountCents = 49_999 }, "$499.99")
	expectScore(t, 20, func(r *ReturnRequest) { r.AmountCents = 50_000 }, "exactly $500 counts")
	expectScore(t, 20, func(r *ReturnRequest) { r.AmountCents = 99_999 }, "$999.99")
	expectScore(t, 35, func(r *ReturnRequest) { r.AmountCents = 100_000 }, "exactly $1000")
}

// Test: frequent returner boundaries: 2 / 3 / 5 / 6
func TestReturnBoundaries(t *testing.T) {
	for n, want := range map[int]int{2: 0, 3: 15, 5: 15, 6: 30} {
		n := n
		expectScore(t, want, func(r *ReturnRequest) { r.ReturnsLast90Days = n }, "returns")
	}
}

// Test: opened electronics needs both conditions
func TestOpenedElectronics(t *testing.T) {
	expectScore(t, 10, func(r *ReturnRequest) { r.Category, r.Opened = Electronics, true }, "opened electronics")
	expectScore(t, 0, func(r *ReturnRequest) { r.Category, r.Opened = Electronics, false }, "unopened electronics")
	expectScore(t, 0, func(r *ReturnRequest) { r.Category, r.Opened = Apparel, true }, "opened apparel")
}

// Test: level boundaries: <30 LOW, 30 MEDIUM, 55 MEDIUM, 60 HIGH
func TestLevels(t *testing.T) {
	r := req()
	r.DaysSincePurchase = 45
	if lvl := assess(t, r).Level; lvl != Low {
		t.Fatalf("25 points: want LOW, got %s", lvl)
	}
	r = req()
	r.Category, r.Opened, r.HasReceipt = Electronics, true, false
	if res := assess(t, r); res.Score != 30 || res.Level != Medium {
		t.Fatalf("want 30/MEDIUM, got %d/%s", res.Score, res.Level)
	}
	r = req()
	r.AmountCents, r.DaysSincePurchase = 100_000, 31
	if res := assess(t, r); res.Score != 60 || res.Level != High {
		t.Fatalf("60 is HIGH: got %d/%s", res.Score, res.Level)
	}
	r = req()
	r.DaysSincePurchase, r.ReturnsLast90Days = 61, 3
	if lvl := assess(t, r).Level; lvl != Medium {
		t.Fatalf("55 points: want MEDIUM, got %s", lvl)
	}
}

// Test: score is capped at 100 and reasons are in rulebook order
func TestCapAndOrder(t *testing.T) {
	r := &ReturnRequest{OrderID: "o", AmountCents: 250_000, DaysSincePurchase: 90, ReturnsLast90Days: 10,
		Category: Electronics, Opened: true, HasReceipt: false}
	res := assess(t, r)
	if res.Score != 100 || res.Level != High {
		t.Fatalf("40+35+30+10+20 = 135 must cap at 100/HIGH, got %d/%s", res.Score, res.Level)
	}
	want := []string{"LATE_RETURN", "HIGH_VALUE", "FREQUENT_RETURNER", "OPENED_ELECTRONICS", "NO_RECEIPT"}
	if !reflect.DeepEqual(res.Reasons, want) {
		t.Fatalf("reasons: want %v, got %v", want, res.Reasons)
	}
}

// Test: validation
func TestValidation(t *testing.T) {
	bad := map[string]func(r *ReturnRequest){
		"blank order id":        func(r *ReturnRequest) { r.OrderID = " " },
		"negative amount":       func(r *ReturnRequest) { r.AmountCents = -1 },
		"negative days":         func(r *ReturnRequest) { r.DaysSincePurchase = -1 },
		"negative return count": func(r *ReturnRequest) { r.ReturnsLast90Days = -2 },
		"missing category":      func(r *ReturnRequest) { r.Category = 0 },
	}
	for why, change := range bad {
		r := req()
		change(r)
		if _, err := Assess(r); !errors.Is(err, ErrInvalidRequest) {
			t.Fatalf("%s: want ErrInvalidRequest, got %v", why, err)
		}
	}
	if _, err := Assess(nil); !errors.Is(err, ErrInvalidRequest) {
		t.Fatalf("nil request: want ErrInvalidRequest, got %v", err)
	}
}

// Test: batch keeps order and isolates bad rows
func TestBatch(t *testing.T) {
	a := req()
	a.OrderID = "a"
	b := req()
	b.OrderID, b.AmountCents = "b", -5
	d := req()
	d.OrderID, d.AmountCents = "d", 60_000
	out := AssessBatch([]*ReturnRequest{a, b, nil, d})
	if len(out) != 4 {
		t.Fatalf("one entry per input: want 4, got %d", len(out))
	}
	if out[0].Err != nil || out[0].OrderID != "a" || out[0].Result == nil {
		t.Fatalf("row a: %+v", out[0])
	}
	if out[1].Err == nil || out[1].OrderID != "b" {
		t.Fatalf("negative amount row must be an error entry: %+v", out[1])
	}
	if out[2].Err == nil || out[2].OrderID != "" {
		t.Fatalf("nil row must be an error entry with no order id: %+v", out[2])
	}
	if out[3].Err != nil || out[3].Result == nil || out[3].Result.Score != 20 {
		t.Fatalf("row d: %+v", out[3])
	}
}
