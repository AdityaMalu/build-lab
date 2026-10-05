package ratelimiter

import "errors"

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

// TokenBucket refills each client's bucket continuously.
type TokenBucket struct {
	// TODO: fields
}

// NewTokenBucket validates its arguments and creates a limiter.
func NewTokenBucket(capacity int, refillPerSecond float64, clock Clock) (*TokenBucket, error) {
	// TODO validate arguments and set up per-client state
	return nil, errors.New("TODO")
}

// TryAcquire refills the client's bucket based on elapsed time, then tries to take one token.
func (b *TokenBucket) TryAcquire(clientID string) (bool, error) {
	// TODO
	return false, errors.New("TODO")
}

// SlidingWindow allows at most maxRequests per client in any window.
type SlidingWindow struct {
	// TODO: fields
}

// NewSlidingWindow validates its arguments and creates a limiter.
func NewSlidingWindow(maxRequests int, windowMillis int64, clock Clock) (*SlidingWindow, error) {
	// TODO
	return nil, errors.New("TODO")
}

// TryAcquire drops timestamps that fell out of the window, then decides.
func (w *SlidingWindow) TryAcquire(clientID string) (bool, error) {
	// TODO
	return false, errors.New("TODO")
}
