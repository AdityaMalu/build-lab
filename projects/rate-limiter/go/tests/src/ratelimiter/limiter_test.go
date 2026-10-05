package ratelimiter

import (
	"errors"
	"sync"
	"sync/atomic"
	"testing"
)

type manualClock struct{ now atomic.Int64 }

func newClock() *manualClock {
	c := &manualClock{}
	c.now.Store(1_000_000)
	return c
}

func (c *manualClock) NowMillis() int64 { return c.now.Load() }
func (c *manualClock) advance(ms int64) { c.now.Add(ms) }

func mustBucket(t *testing.T, capacity int, refill float64, c Clock) *TokenBucket {
	t.Helper()
	b, err := NewTokenBucket(capacity, refill, c)
	if err != nil || b == nil {
		t.Fatalf("NewTokenBucket(%d, %v) failed: %v", capacity, refill, err)
	}
	return b
}

func mustWindow(t *testing.T, max int, window int64, c Clock) *SlidingWindow {
	t.Helper()
	w, err := NewSlidingWindow(max, window, c)
	if err != nil || w == nil {
		t.Fatalf("NewSlidingWindow(%d, %d) failed: %v", max, window, err)
	}
	return w
}

func allowed(t *testing.T, l Limiter, client string) bool {
	t.Helper()
	ok, err := l.TryAcquire(client)
	if err != nil {
		t.Fatalf("TryAcquire(%q) returned error: %v", client, err)
	}
	return ok
}

// Test: token bucket: starts full and allows exactly `capacity` requests
func TestBucketStartsFull(t *testing.T) {
	b := mustBucket(t, 5, 1, newClock())
	for i := 0; i < 5; i++ {
		if !allowed(t, b, "a") {
			t.Fatalf("request %d should pass", i+1)
		}
	}
	if allowed(t, b, "a") {
		t.Fatal("6th request should be rejected")
	}
}

// Test: token bucket: refills over time
func TestBucketRefills(t *testing.T) {
	c := newClock()
	b := mustBucket(t, 2, 2, c) // 1 token every 500ms
	allowed(t, b, "a")
	allowed(t, b, "a")
	if allowed(t, b, "a") {
		t.Fatal("bucket should be empty")
	}
	c.advance(499)
	if allowed(t, b, "a") {
		t.Fatal("not quite one token yet")
	}
	c.advance(1)
	if !allowed(t, b, "a") {
		t.Fatal("one token after 500ms")
	}
	if allowed(t, b, "a") {
		t.Fatal("bucket should be empty again")
	}
}

// Test: token bucket: fractional refill accumulates across calls
func TestBucketFractional(t *testing.T) {
	c := newClock()
	b := mustBucket(t, 1, 1, c)
	allowed(t, b, "a")
	for i := 1; i <= 4; i++ {
		c.advance(200)
		if allowed(t, b, "a") {
			t.Fatalf("only %dms elapsed", 200*i)
		}
	}
	c.advance(200)
	if !allowed(t, b, "a") {
		t.Fatal("1000ms in 200ms steps must add up to one token")
	}
}

// Test: token bucket: never exceeds capacity after a long idle period
func TestBucketCapped(t *testing.T) {
	c := newClock()
	b := mustBucket(t, 3, 10, c)
	c.advance(60_000)
	n := 0
	for i := 0; i < 10; i++ {
		if allowed(t, b, "a") {
			n++
		}
	}
	if n != 3 {
		t.Fatalf("capacity caps the burst: want 3, got %d", n)
	}
}

// Test: token bucket: clients are isolated
func TestBucketIsolation(t *testing.T) {
	b := mustBucket(t, 1, 0, newClock())
	allowed(t, b, "a")
	if allowed(t, b, "a") {
		t.Fatal("a should be empty")
	}
	if !allowed(t, b, "b") {
		t.Fatal("client b has its own bucket")
	}
}

// Test: token bucket: validates arguments
func TestBucketValidation(t *testing.T) {
	c := newClock()
	for _, tc := range []struct {
		capacity int
		refill   float64
		clock    Clock
	}{{0, 1, c}, {1, -1, c}, {1, 1, nil}} {
		if _, err := NewTokenBucket(tc.capacity, tc.refill, tc.clock); !errors.Is(err, ErrInvalidArgument) {
			t.Fatalf("NewTokenBucket(%d, %v, clock=%v): want ErrInvalidArgument, got %v", tc.capacity, tc.refill, tc.clock != nil, err)
		}
	}
	b := mustBucket(t, 1, 1, c)
	if ok, err := b.TryAcquire("  "); ok || !errors.Is(err, ErrInvalidArgument) {
		t.Fatalf("blank client: want (false, ErrInvalidArgument), got (%v, %v)", ok, err)
	}
}

// Test: token bucket: 100 goroutines cannot exceed capacity
func TestBucketConcurrency(t *testing.T) {
	b := mustBucket(t, 50, 0, newClock())
	var n atomic.Int32
	var wg sync.WaitGroup
	start := make(chan struct{})
	for g := 0; g < 100; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			for k := 0; k < 20; k++ {
				if ok, _ := b.TryAcquire("hot"); ok {
					n.Add(1)
				}
			}
		}()
	}
	close(start)
	wg.Wait()
	if n.Load() != 50 {
		t.Fatalf("exactly capacity requests may pass: want 50, got %d", n.Load())
	}
}

// Test: sliding window: allows maxRequests then rejects
func TestWindowBasic(t *testing.T) {
	w := mustWindow(t, 3, 1000, newClock())
	for i := 0; i < 3; i++ {
		if !allowed(t, w, "a") {
			t.Fatalf("request %d should pass", i+1)
		}
	}
	if allowed(t, w, "a") {
		t.Fatal("4th request should be rejected")
	}
}

// Test: sliding window: old requests slide out exactly at the boundary
func TestWindowSlides(t *testing.T) {
	c := newClock()
	w := mustWindow(t, 2, 1000, c)
	allowed(t, w, "a") // t=0
	c.advance(400)
	allowed(t, w, "a") // t=400
	c.advance(599)
	if allowed(t, w, "a") {
		t.Fatal("t=999: both still inside the window")
	}
	c.advance(1)
	if !allowed(t, w, "a") {
		t.Fatal("t=1000: first request left the window")
	}
	if allowed(t, w, "a") {
		t.Fatal("window full again")
	}
	c.advance(400)
	if !allowed(t, w, "a") {
		t.Fatal("t=1400: second request left the window")
	}
}

// Test: sliding window: rejected requests do not count
func TestWindowRejectedNotCounted(t *testing.T) {
	c := newClock()
	w := mustWindow(t, 1, 1000, c)
	allowed(t, w, "a")
	for i := 0; i < 5; i++ {
		c.advance(100)
		if allowed(t, w, "a") {
			t.Fatal("should be rejected")
		}
	}
	c.advance(500)
	if !allowed(t, w, "a") {
		t.Fatal("rejections must not extend the block")
	}
}

// Test: sliding window: validates arguments
func TestWindowValidation(t *testing.T) {
	c := newClock()
	if _, err := NewSlidingWindow(0, 1000, c); !errors.Is(err, ErrInvalidArgument) {
		t.Fatalf("maxRequests 0: want ErrInvalidArgument, got %v", err)
	}
	if _, err := NewSlidingWindow(1, 0, c); !errors.Is(err, ErrInvalidArgument) {
		t.Fatalf("window 0: want ErrInvalidArgument, got %v", err)
	}
	if _, err := NewSlidingWindow(1, 1000, nil); !errors.Is(err, ErrInvalidArgument) {
		t.Fatalf("nil clock: want ErrInvalidArgument, got %v", err)
	}
	w := mustWindow(t, 1, 1000, c)
	if _, err := w.TryAcquire(""); !errors.Is(err, ErrInvalidArgument) {
		t.Fatalf("empty client: want ErrInvalidArgument, got %v", err)
	}
}

// Test: sliding window: 100 goroutines cannot exceed the limit
func TestWindowConcurrency(t *testing.T) {
	w := mustWindow(t, 40, 10_000, newClock())
	var n atomic.Int32
	var wg sync.WaitGroup
	start := make(chan struct{})
	for g := 0; g < 100; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			for k := 0; k < 10; k++ {
				if ok, _ := w.TryAcquire("hot"); ok {
					n.Add(1)
				}
			}
		}()
	}
	close(start)
	wg.Wait()
	if n.Load() != 40 {
		t.Fatalf("exactly maxRequests may pass: want 40, got %d", n.Load())
	}
}
