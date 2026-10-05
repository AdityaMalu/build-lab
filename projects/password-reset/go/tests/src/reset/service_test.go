package reset

import (
	"crypto/rand"
	"errors"
	"fmt"
	"math/big"
	"regexp"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

var t0 = time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC).UnixMilli()

const email = "ana@example.com"

type mutableClock struct{ now atomic.Int64 }

func newClock() *mutableClock {
	c := &mutableClock{}
	c.now.Store(t0)
	return c
}

func (c *mutableClock) NowMillis() int64 { return c.now.Load() }

func store() *MemoryStore { return NewMemoryStore().Add(email, HashPassword("old-password")) }

func fixed(v int64) func(int64) int64 { return func(bound int64) int64 { return v % bound } }

func newSvc(t *testing.T, cfg map[string]string, c Clock, users UserStore, codes func(int64) int64) *Service {
	t.Helper()
	if c == nil {
		c = newClock()
	}
	if users == nil {
		users = store()
	}
	if codes == nil {
		codes = fixed(1)
	}
	s, err := NewService(cfg, c, users, codes)
	if err != nil || s == nil {
		t.Fatalf("NewService(%v) failed: %v", cfg, err)
	}
	return s
}

func request(t *testing.T, s *Service) string {
	t.Helper()
	code, err := s.RequestReset(email)
	if err != nil {
		t.Fatalf("RequestReset: %v", err)
	}
	return code
}

func confirm(t *testing.T, s *Service, code, pw string) bool {
	t.Helper()
	ok, err := s.ConfirmReset(email, code, pw)
	if err != nil {
		t.Fatalf("ConfirmReset: %v", err)
	}
	return ok
}

// Test: codes are zero-padded to the configured length
func TestPadding(t *testing.T) {
	if code := request(t, newSvc(t, nil, nil, nil, fixed(42))); code != "000042" {
		t.Fatalf("want 000042, got %q", code)
	}
	if code := request(t, newSvc(t, map[string]string{"reset.code.length": "8"}, nil, nil, fixed(7))); code != "00000007" {
		t.Fatalf("want 00000007, got %q", code)
	}
}

// Test: random codes always have exactly `length` digits
func TestLengthAlways(t *testing.T) {
	random := func(bound int64) int64 {
		n, _ := rand.Int(rand.Reader, big.NewInt(bound))
		return n.Int64()
	}
	s := newSvc(t, nil, nil, nil, random)
	re := regexp.MustCompile(`^\d{6}$`)
	for i := 0; i < 300; i++ {
		if code := request(t, s); !re.MatchString(code) {
			t.Fatalf("bad code %q", code)
		}
	}
}

// Test: ttl comes from reset.ttl.minutes and the injected clock
func TestTTLFromConfig(t *testing.T) {
	c := newClock()
	s := newSvc(t, map[string]string{"reset.ttl.minutes": "30"}, c, nil, nil)
	request(t, s)
	if at, _ := s.ExpiresAt(email); at != t0+30*60_000 {
		t.Fatalf("want t0+30min, got t0%+d ms", at-t0)
	}
	d := newSvc(t, nil, c, nil, nil)
	request(t, d)
	if at, _ := d.ExpiresAt(email); at != t0+15*60_000 {
		t.Fatalf("default 15 minutes: got t0%+d ms", at-t0)
	}
}

// Test: DescribeExpiry uses the configured UTC offset
func TestTimezoneOffset(t *testing.T) {
	cases := []struct {
		offset, want string
	}{{"330", "2026-03-01 15:45 UTC+05:30"}, {"-90", "2026-03-01 08:45 UTC-01:30"}, {"0", "2026-03-01 10:15 UTC"}}
	for _, c := range cases {
		s := newSvc(t, map[string]string{"reset.utc.offset.minutes": c.offset}, nil, nil, nil)
		request(t, s)
		if got, err := s.DescribeExpiry(email); err != nil || got != c.want {
			t.Fatalf("offset %s: want %q, got %q (%v)", c.offset, c.want, got, err)
		}
	}
}

// Test: bad configuration is rejected up front
func TestBadConfig(t *testing.T) {
	bad := []map[string]string{
		{"reset.code.length": "3"}, {"reset.code.length": "11"}, {"reset.ttl.minutes": "abc"},
		{"reset.ttl.minutes": "0"}, {"reset.utc.offset.minutes": "x"}, {"reset.utc.offset.minutes": "900"},
	}
	for _, cfg := range bad {
		if _, err := NewService(cfg, newClock(), store(), fixed(1)); !errors.Is(err, ErrInvalidConfig) {
			t.Fatalf("%v: want ErrInvalidConfig, got %v", cfg, err)
		}
	}
}

// Test: successful reset stores the hash, never the raw password
func TestStoresHash(t *testing.T) {
	users := store()
	s := newSvc(t, nil, nil, users, fixed(123456))
	code := request(t, s)
	if !confirm(t, s, code, "brand-new-pass") {
		t.Fatal("reset should succeed")
	}
	if got := users.PasswordHash(email); got != HashPassword("brand-new-pass") {
		t.Fatalf("stored %q, want the hash (plain text stored?)", got)
	}
}

// Test: codes are single-use
func TestSingleUse(t *testing.T) {
	s := newSvc(t, nil, nil, nil, fixed(555))
	code := request(t, s)
	if !confirm(t, s, code, "first-new-pass") {
		t.Fatal("first use should succeed")
	}
	if confirm(t, s, code, "second-new-pass") {
		t.Fatal("code reused")
	}
	if _, err := s.ExpiresAt(email); !errors.Is(err, ErrNoPendingReset) {
		t.Fatalf("nothing pending after use: got %v", err)
	}
}

// Test: expiry boundary: valid 1ms before, invalid exactly at expiry
func TestExpiryBoundary(t *testing.T) {
	c := newClock()
	s := newSvc(t, nil, c, nil, fixed(9))
	code := request(t, s)
	c.now.Add(15*60_000 - 1)
	if !confirm(t, s, code, "just-in-time") {
		t.Fatal("1ms before expiry must work")
	}
	code2 := request(t, s)
	c.now.Add(15 * 60_000)
	if confirm(t, s, code2, "too-late-now") {
		t.Fatal("exactly at expiry must fail")
	}
}

// Test: a new request replaces the old code
func TestReplaces(t *testing.T) {
	var n atomic.Int64
	n.Store(100)
	s := newSvc(t, nil, nil, nil, func(int64) int64 { return n.Add(1) })
	first := request(t, s)
	second := request(t, s)
	if first == second {
		t.Fatal("test setup: codes should differ")
	}
	if confirm(t, s, first, "password-1") {
		t.Fatal("old code is dead")
	}
	if !confirm(t, s, second, "password-2") {
		t.Fatal("new code works")
	}
}

// Test: short password is rejected without consuming the code
func TestShortPassword(t *testing.T) {
	s := newSvc(t, nil, nil, nil, fixed(31337))
	code := request(t, s)
	if _, err := s.ConfirmReset(email, code, "short"); !errors.Is(err, ErrWeakPassword) {
		t.Fatalf("want ErrWeakPassword, got %v", err)
	}
	if !confirm(t, s, code, "long-enough") {
		t.Fatal("code still usable")
	}
}

// Test: five wrong guesses invalidate the code
func TestAttemptLimit(t *testing.T) {
	s := newSvc(t, nil, nil, nil, fixed(424242))
	code := request(t, s)
	for i := 0; i < 5; i++ {
		confirm(t, s, "000000", "whatever-pass")
	}
	if confirm(t, s, code, "whatever-pass") {
		t.Fatal("locked out after 5 failures")
	}
}

// Test: four wrong guesses still allow the right one
func TestUnderLimit(t *testing.T) {
	s := newSvc(t, nil, nil, nil, fixed(424242))
	code := request(t, s)
	for i := 0; i < 4; i++ {
		confirm(t, s, "111111", "whatever-pass")
	}
	if !confirm(t, s, code, "whatever-pass") {
		t.Fatal("4 wrong guesses must not lock out")
	}
}

// Test: unknown users
func TestUnknown(t *testing.T) {
	s := newSvc(t, nil, nil, nil, nil)
	if _, err := s.RequestReset("ghost@example.com"); !errors.Is(err, ErrUnknownUser) {
		t.Fatalf("want ErrUnknownUser, got %v", err)
	}
	if ok, _ := s.ConfirmReset("ghost@example.com", "000001", "some-password"); ok {
		t.Fatal("unknown user can't reset")
	}
}

// Test: racing confirmations with the same code: only one succeeds
func TestRace(t *testing.T) {
	for round := 0; round < 20; round++ {
		s := newSvc(t, nil, nil, nil, fixed(777))
		code := request(t, s)
		var wins atomic.Int32
		var wg sync.WaitGroup
		start := make(chan struct{})
		for i := 0; i < 8; i++ {
			wg.Add(1)
			go func(i int) {
				defer wg.Done()
				<-start
				if ok, _ := s.ConfirmReset(email, code, fmt.Sprint("racer-pass-", i)); ok {
					wins.Add(1)
				}
			}(i)
		}
		close(start)
		wg.Wait()
		if wins.Load() != 1 {
			t.Fatalf("round %d: want exactly 1 winner, got %d", round, wins.Load())
		}
	}
}
