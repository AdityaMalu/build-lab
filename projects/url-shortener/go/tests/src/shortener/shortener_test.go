package shortener

import (
	"errors"
	"fmt"
	"math"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

type manualClock struct{ now atomic.Int64 }

func newClock() *manualClock {
	c := &manualClock{}
	c.now.Store(5_000)
	return c
}

func (c *manualClock) NowMillis() int64 { return c.now.Load() }

func mustShorten(t *testing.T, s *Shortener, url string, ttl int64) string {
	t.Helper()
	code, err := s.Shorten(url, ttl)
	if err != nil {
		t.Fatalf("Shorten(%q) failed: %v", url, err)
	}
	return code
}

// Test: base62 encodes known values
func TestBase62Encode(t *testing.T) {
	cases := map[int64]string{0: "0", 9: "9", 10: "A", 61: "z", 62: "10", 62*62 - 1: "zz"}
	for n, want := range cases {
		got, err := Encode(n)
		if err != nil || got != want {
			t.Fatalf("Encode(%d) = %q, %v; want %q", n, got, err, want)
		}
	}
	if _, err := Encode(-1); !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("Encode(-1): want ErrInvalidInput, got %v", err)
	}
}

// Test: base62 round-trips and rejects bad input
func TestBase62RoundTrip(t *testing.T) {
	for _, n := range []int64{0, 1, 61, 62, 3843, 916_132_832, 123_456_789_012, math.MaxInt64} {
		code, err := Encode(n)
		if err != nil {
			t.Fatalf("Encode(%d): %v", n, err)
		}
		back, err := Decode(code)
		if err != nil || back != n {
			t.Fatalf("round trip %d -> %q -> %d (%v)", n, code, back, err)
		}
	}
	for _, bad := range []string{"", "ab-c"} {
		if _, err := Decode(bad); !errors.Is(err, ErrInvalidInput) {
			t.Fatalf("Decode(%q): want ErrInvalidInput, got %v", bad, err)
		}
	}
}

// Test: Shorten returns a 6-10 char base62 code that resolves
func TestShortenResolve(t *testing.T) {
	s := NewShortener(newClock())
	code := mustShorten(t, s, "https://example.com/a?b=c", 0)
	if !regexp.MustCompile(`^[0-9A-Za-z]{6,10}$`).MatchString(code) {
		t.Fatalf("bad code format: %q", code)
	}
	if url, ok := s.Resolve(code); !ok || url != "https://example.com/a?b=c" {
		t.Fatalf("Resolve = %q, %v", url, ok)
	}
	if _, ok := s.Resolve("nope123"); ok {
		t.Fatal("unknown code must not resolve")
	}
}

// Test: different urls get different codes, same url is deduplicated
func TestDedup(t *testing.T) {
	s := NewShortener(newClock())
	a := mustShorten(t, s, "https://a.com", 0)
	b := mustShorten(t, s, "https://b.com", 0)
	if a == b {
		t.Fatal("distinct urls need distinct codes")
	}
	if again := mustShorten(t, s, "https://a.com", 0); again != a {
		t.Fatalf("same url should reuse its code: %q vs %q", again, a)
	}
}

// Test: dedup does not reuse an expired link
func TestDedupExpired(t *testing.T) {
	c := newClock()
	s := NewShortener(c)
	first := mustShorten(t, s, "https://promo.com", 1000)
	c.now.Add(1000)
	second := mustShorten(t, s, "https://promo.com", 1000)
	if first == second {
		t.Fatal("expired link must not be handed out again")
	}
	if _, ok := s.Resolve(second); !ok {
		t.Fatal("new link must resolve")
	}
}

// Test: url validation
func TestURLValidation(t *testing.T) {
	s := NewShortener(newClock())
	for _, bad := range []string{"", "ftp://x.com", "example.com", "https://", "http://has space.com",
		"https://" + strings.Repeat("a", 2050)} {
		if _, err := s.Shorten(bad, 0); !errors.Is(err, ErrInvalidURL) {
			t.Fatalf("Shorten(%.30q): want ErrInvalidURL, got %v", bad, err)
		}
	}
	mustShorten(t, s, "http://x.io", 0)
}

// Test: expiry boundary: valid before t+ttl, expired at t+ttl
func TestExpiry(t *testing.T) {
	c := newClock()
	s := NewShortener(c)
	code := mustShorten(t, s, "https://sale.com", 10_000)
	c.now.Add(9_999)
	if _, ok := s.Resolve(code); !ok {
		t.Fatal("1ms before expiry it must resolve")
	}
	c.now.Add(1)
	if _, ok := s.Resolve(code); ok {
		t.Fatal("exactly at expiry it must not resolve")
	}
	st, err := s.Stats(code)
	if err != nil || st.Hits != 1 {
		t.Fatalf("expired resolve must not count: hits=%d err=%v", st.Hits, err)
	}
}

// Test: aliases: format, collisions, resolution
func TestAliases(t *testing.T) {
	s := NewShortener(newClock())
	if code, err := s.ShortenWithAlias("https://shop.com/s", "summer-sale_24", 0); err != nil || code != "summer-sale_24" {
		t.Fatalf("ShortenWithAlias = %q, %v", code, err)
	}
	if url, ok := s.Resolve("summer-sale_24"); !ok || url != "https://shop.com/s" {
		t.Fatal("alias must resolve")
	}
	if _, err := s.ShortenWithAlias("https://other.com", "summer-sale_24", 0); !errors.Is(err, ErrAliasTaken) {
		t.Fatalf("duplicate alias: want ErrAliasTaken, got %v", err)
	}
	for _, bad := range []string{"ab", "has space", strings.Repeat("x", 33)} {
		if _, err := s.ShortenWithAlias("https://x.com", bad, 0); !errors.Is(err, ErrInvalidAlias) {
			t.Fatalf("alias %q: want ErrInvalidAlias, got %v", bad, err)
		}
	}
	gen := mustShorten(t, s, "https://gen.com", 0)
	if _, err := s.ShortenWithAlias("https://x.com", gen, 0); !errors.Is(err, ErrAliasTaken) {
		t.Fatalf("alias may not steal a generated code: got %v", err)
	}
}

// Test: an alias does not satisfy dedup for Shorten()
func TestAliasNotDeduped(t *testing.T) {
	s := NewShortener(newClock())
	if _, err := s.ShortenWithAlias("https://same.com", "mine", 0); err != nil {
		t.Fatal(err)
	}
	if code := mustShorten(t, s, "https://same.com", 0); code == "mine" {
		t.Fatal("Shorten must not hand out the alias")
	}
}

// Test: stats track hits and last access
func TestStats(t *testing.T) {
	c := newClock()
	s := NewShortener(c)
	code := mustShorten(t, s, "https://a.com", 0)
	fresh, err := s.Stats(code)
	if err != nil || fresh.Hits != 0 || fresh.LastAccessMillis != -1 {
		t.Fatalf("fresh stats = %+v, %v; want 0 hits, last access -1", fresh, err)
	}
	s.Resolve(code)
	c.now.Store(7_777)
	s.Resolve(code)
	after, _ := s.Stats(code)
	if after.Hits != 2 || after.LastAccessMillis != 7_777 {
		t.Fatalf("stats = %+v; want 2 hits at 7777", after)
	}
	if _, err := s.Stats("missing"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown code: want ErrNotFound, got %v", err)
	}
}

// Test: concurrent Shorten gives unique codes, concurrent Resolve loses no hits
func TestConcurrency(t *testing.T) {
	s := NewShortener(newClock())
	var mu sync.Mutex
	codes := map[string]bool{}
	var wg sync.WaitGroup
	for g := 0; g < 20; g++ {
		wg.Add(1)
		go func(g int) {
			defer wg.Done()
			for k := 0; k < 100; k++ {
				code, err := s.Shorten(fmt.Sprintf("https://site.com/%d/%d", g, k), 0)
				if err != nil {
					t.Error(err)
					return
				}
				mu.Lock()
				codes[code] = true
				mu.Unlock()
			}
		}(g)
	}
	wg.Wait()
	if len(codes) != 2000 {
		t.Fatalf("every url gets its own code: want 2000, got %d", len(codes))
	}
	hot := mustShorten(t, s, "https://hot.com", 0)
	for g := 0; g < 20; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for k := 0; k < 500; k++ {
				s.Resolve(hot)
			}
		}()
	}
	wg.Wait()
	if st, _ := s.Stats(hot); st.Hits != 10_000 {
		t.Fatalf("lost hits: want 10000, got %d", st.Hits)
	}
}
