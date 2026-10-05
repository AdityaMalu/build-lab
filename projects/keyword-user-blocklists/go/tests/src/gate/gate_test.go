package gate

import (
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
)

type manualClock struct{ now atomic.Int64 }

func newClock() *manualClock {
	c := &manualClock{}
	c.now.Store(10_000_000)
	return c
}

func (c *manualClock) NowMillis() int64 { return c.now.Load() }
func (c *manualClock) advance(ms int64) { c.now.Add(ms) }

func newTestGate(t *testing.T, c Clock) *Gate {
	t.Helper()
	g := NewGate(c)
	for _, k := range []string{"spam", "Free Money"} {
		if err := g.AddKeyword(k); err != nil {
			t.Fatalf("AddKeyword(%q): %v", k, err)
		}
	}
	return g
}

func submit(t *testing.T, g *Gate, user, text string) Decision {
	t.Helper()
	d, err := g.Submit(user, text)
	if err != nil {
		t.Fatalf("Submit(%q, %q): %v", user, text, err)
	}
	return d
}

func expect(t *testing.T, want, got Decision, why string) {
	t.Helper()
	if want != got {
		t.Fatalf("%s: want %s, got %s", why, want, got)
	}
}

// Test: clean posts are accepted
func TestAccepted(t *testing.T) {
	g := newTestGate(t, newClock())
	expect(t, Accepted, submit(t, g, "u", "Hello there, nice weather"), "clean post")
	expect(t, Accepted, submit(t, g, "u", ""), "empty text")
	if g.ActiveStrikes("u") != 0 {
		t.Fatal("no strikes expected")
	}
}

// Test: single keyword: case-insensitive, whole token
func TestSingleWord(t *testing.T) {
	g := newTestGate(t, newClock())
	expect(t, RejectedKeyword, submit(t, g, "a", "This is SPAM."), "upper case")
	expect(t, RejectedKeyword, submit(t, g, "b", "spam"), "exact")
	expect(t, Accepted, submit(t, g, "c", "spammer alert"), "'spammer' is a different token")
	expect(t, Accepted, submit(t, g, "d", "antispam filter"), "antispam")
}

// Test: phrases match consecutive tokens across punctuation and spacing
func TestPhrases(t *testing.T) {
	g := newTestGate(t, newClock())
	expect(t, RejectedKeyword, submit(t, g, "a", "Get FREE   money!!"), "spacing")
	expect(t, RejectedKeyword, submit(t, g, "b", "free-money now"), "hyphen")
	expect(t, Accepted, submit(t, g, "c", "freemoney"), "one token")
	expect(t, Accepted, submit(t, g, "d", "free the money"), "not consecutive")
	expect(t, Accepted, submit(t, g, "e", "money free"), "wrong order")
}

// Test: keywords can be removed; blank keywords are rejected
func TestKeywordAdmin(t *testing.T) {
	g := newTestGate(t, newClock())
	if err := g.RemoveKeyword("SPAM"); err != nil {
		t.Fatal(err)
	}
	expect(t, Accepted, submit(t, g, "u", "spam"), "removed")
	if err := g.RemoveKeyword("free    money"); err != nil {
		t.Fatal(err)
	}
	expect(t, Accepted, submit(t, g, "u", "free money"), "normalized phrase removal")
	for _, bad := range []string{"   ", "!!!"} {
		if err := g.AddKeyword(bad); !errors.Is(err, ErrInvalidArgument) {
			t.Fatalf("AddKeyword(%q): want ErrInvalidArgument, got %v", bad, err)
		}
	}
	if _, err := g.Submit(" ", "x"); !errors.Is(err, ErrInvalidArgument) {
		t.Fatalf("blank user: want ErrInvalidArgument, got %v", err)
	}
}

// Test: three strikes inside the window auto-block for 24h
func TestAutoBlock(t *testing.T) {
	c := newClock()
	g := newTestGate(t, c)
	expect(t, RejectedKeyword, submit(t, g, "u", "spam"), "strike 1")
	if g.ActiveStrikes("u") != 1 {
		t.Fatalf("want 1 strike, got %d", g.ActiveStrikes("u"))
	}
	c.advance(1000)
	expect(t, RejectedKeyword, submit(t, g, "u", "spam"), "strike 2")
	if g.IsBlocked("u") {
		t.Fatal("not blocked after 2 strikes")
	}
	c.advance(1000)
	expect(t, RejectedKeyword, submit(t, g, "u", "spam"), "third strike itself is a rejection")
	if !g.IsBlocked("u") || g.ActiveStrikes("u") != 0 {
		t.Fatal("blocked after third strike, with strikes cleared")
	}
	expect(t, BlockedUser, submit(t, g, "u", "totally innocent"), "blocked")
	c.advance(AutoBlockMillis - 1)
	expect(t, BlockedUser, submit(t, g, "u", "hi"), "1ms before block expires")
	c.advance(1)
	expect(t, Accepted, submit(t, g, "u", "hi"), "block expired")
}

// Test: strikes expire after the window
func TestStrikeWindow(t *testing.T) {
	c := newClock()
	g := newTestGate(t, c)
	submit(t, g, "u", "spam")
	c.advance(30 * 60 * 1000)
	submit(t, g, "u", "spam")
	c.advance(30 * 60 * 1000) // first strike expires exactly now
	if n := g.ActiveStrikes("u"); n != 1 {
		t.Fatalf("want 1 active strike, got %d", n)
	}
	expect(t, RejectedKeyword, submit(t, g, "u", "spam"), "strike")
	if g.IsBlocked("u") || g.ActiveStrikes("u") != 2 {
		t.Fatal("only 2 strikes in the window: not blocked")
	}
}

// Test: blocked users get no strikes; unblock clears everything
func TestManualBlock(t *testing.T) {
	c := newClock()
	g := newTestGate(t, c)
	submit(t, g, "u", "spam")
	if err := g.BlockUser("u", 0); err != nil {
		t.Fatal(err)
	}
	expect(t, BlockedUser, submit(t, g, "u", "spam"), "blocked")
	c.advance(365 * 24 * 3600 * 1000)
	if !g.IsBlocked("u") {
		t.Fatal("permanent block")
	}
	if err := g.UnblockUser("u"); err != nil {
		t.Fatal(err)
	}
	if g.IsBlocked("u") || g.ActiveStrikes("u") != 0 {
		t.Fatal("unblock lifts the block and clears strikes")
	}
	if err := g.BlockUser("v", 5000); err != nil {
		t.Fatal(err)
	}
	c.advance(5000)
	if g.IsBlocked("v") {
		t.Fatal("timed block ends at now + duration")
	}
}

// Test: users are independent
func TestIndependentUsers(t *testing.T) {
	g := newTestGate(t, newClock())
	for i := 0; i < 3; i++ {
		submit(t, g, "bad", "spam")
	}
	if !g.IsBlocked("bad") {
		t.Fatal("bad must be blocked")
	}
	expect(t, Accepted, submit(t, g, "good", "hello"), "good user")
	if g.ActiveStrikes("good") != 0 {
		t.Fatal("good has no strikes")
	}
}

// Test: 20 concurrent violations: exactly 3 rejections, the rest blocked
func TestConcurrentStrikes(t *testing.T) {
	for round := 0; round < 10; round++ {
		g := newTestGate(t, newClock())
		var rejected, blocked atomic.Int32
		var wg sync.WaitGroup
		start := make(chan struct{})
		for i := 0; i < 20; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				<-start
				d, _ := g.Submit("racer", "free money spam")
				switch d {
				case RejectedKeyword:
					rejected.Add(1)
				case BlockedUser:
					blocked.Add(1)
				}
			}()
		}
		close(start)
		wg.Wait()
		if rejected.Load() != 3 || blocked.Load() != 17 {
			t.Fatalf("round %d: want 3 rejected / 17 blocked, got %d / %d", round, rejected.Load(), blocked.Load())
		}
	}
}

// Test: keyword updates during traffic don't break checks
func TestConcurrentKeywordUpdates(t *testing.T) {
	g := newTestGate(t, newClock())
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for k := 0; k < 500; k++ {
				if i == 0 {
					_ = g.AddKeyword(fmt.Sprint("temp", k))
					_ = g.RemoveKeyword(fmt.Sprint("temp", k-1))
					continue
				}
				d, err := g.Submit(fmt.Sprintf("user%d-%d", i, k), "a perfectly normal sentence")
				if err != nil || d != Accepted {
					t.Errorf("want Accepted, got %s / %v", d, err)
					return
				}
			}
		}(i)
	}
	wg.Wait()
}
