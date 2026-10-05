package ratelimiter

import (
	"errors"
	"fmt"
	"math"
	"strings"
	"sync"
)

// Clock is injected so tests can control time.
type Clock interface {
	NowMillis() int64
}

// Limiter decides whether a client's request may go through.
type Limiter interface {
	TryAcquire(clientID string) (bool, error)
}

// ErrInvalidArgument is wrapped by every validation error.
var ErrInvalidArgument = errors.New("invalid argument")

const epsilon = 1e-9

func checkClient(clientID string) error {
	if strings.TrimSpace(clientID) == "" {
		return fmt.Errorf("client id required: %w", ErrInvalidArgument)
	}
	return nil
}

type bucket struct {
	mu         sync.Mutex
	tokens     float64
	lastRefill int64
}

// TokenBucket refills each client's bucket continuously.
type TokenBucket struct {
	capacity       float64
	refillPerMilli float64
	clock          Clock
	mu             sync.Mutex // guards buckets only; each bucket has its own lock
	buckets        map[string]*bucket
}

// NewTokenBucket validates its arguments and creates a limiter.
func NewTokenBucket(capacity int, refillPerSecond float64, clock Clock) (*TokenBucket, error) {
	switch {
	case capacity <= 0:
		return nil, fmt.Errorf("capacity must be positive: %w", ErrInvalidArgument)
	case refillPerSecond < 0 || math.IsNaN(refillPerSecond):
		return nil, fmt.Errorf("refill must be >= 0: %w", ErrInvalidArgument)
	case clock == nil:
		return nil, fmt.Errorf("clock required: %w", ErrInvalidArgument)
	}
	return &TokenBucket{
		capacity:       float64(capacity),
		refillPerMilli: refillPerSecond / 1000,
		clock:          clock,
		buckets:        map[string]*bucket{},
	}, nil
}

// TryAcquire refills the client's bucket based on elapsed time, then tries to take one token.
func (b *TokenBucket) TryAcquire(clientID string) (bool, error) {
	if err := checkClient(clientID); err != nil {
		return false, err
	}
	b.mu.Lock()
	bk, ok := b.buckets[clientID]
	if !ok {
		bk = &bucket{tokens: b.capacity, lastRefill: b.clock.NowMillis()}
		b.buckets[clientID] = bk
	}
	b.mu.Unlock()

	bk.mu.Lock()
	defer bk.mu.Unlock()
	now := b.clock.NowMillis()
	elapsed := now - bk.lastRefill
	if elapsed < 0 {
		elapsed = 0
	}
	bk.tokens = math.Min(b.capacity, bk.tokens+float64(elapsed)*b.refillPerMilli)
	bk.lastRefill = now
	if bk.tokens >= 1-epsilon {
		bk.tokens = math.Max(0, bk.tokens-1)
		return true, nil
	}
	return false, nil
}

type window struct {
	mu    sync.Mutex
	times []int64
}

// SlidingWindow allows at most maxRequests per client in any window.
type SlidingWindow struct {
	max     int
	window  int64
	clock   Clock
	mu      sync.Mutex
	windows map[string]*window
}

// NewSlidingWindow validates its arguments and creates a limiter.
func NewSlidingWindow(maxRequests int, windowMillis int64, clock Clock) (*SlidingWindow, error) {
	switch {
	case maxRequests <= 0:
		return nil, fmt.Errorf("maxRequests must be positive: %w", ErrInvalidArgument)
	case windowMillis <= 0:
		return nil, fmt.Errorf("windowMillis must be positive: %w", ErrInvalidArgument)
	case clock == nil:
		return nil, fmt.Errorf("clock required: %w", ErrInvalidArgument)
	}
	return &SlidingWindow{max: maxRequests, window: windowMillis, clock: clock, windows: map[string]*window{}}, nil
}

// TryAcquire drops timestamps that fell out of the window, then decides.
func (s *SlidingWindow) TryAcquire(clientID string) (bool, error) {
	if err := checkClient(clientID); err != nil {
		return false, err
	}
	s.mu.Lock()
	w, ok := s.windows[clientID]
	if !ok {
		w = &window{}
		s.windows[clientID] = w
	}
	s.mu.Unlock()

	w.mu.Lock()
	defer w.mu.Unlock()
	now := s.clock.NowMillis()
	drop := 0
	for drop < len(w.times) && w.times[drop] <= now-s.window {
		drop++
	}
	w.times = w.times[drop:]
	if len(w.times) < s.max {
		w.times = append(w.times, now)
		return true, nil
	}
	return false, nil
}
