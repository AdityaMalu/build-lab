package reset

import (
	"crypto/subtle"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"
)

const maxAttempts = 5

type pending struct {
	code      string
	expiresAt int64
	failures  int
}

// Service issues and confirms reset codes.
//
// Fixes: correct config key with validation; zero-padded fixed-length codes; expiry from the injected clock;
// DescribeExpiry honours the offset; expired at now >= expiry; password validated first; hash stored; code consumed;
// at most 5 wrong guesses; constant-time comparison; one mutex for check-and-consume.
type Service struct {
	clock   Clock
	users   UserStore
	codes   func(int64) int64
	ttl     int64 // millis
	length  int
	offset  int // minutes
	mu      sync.Mutex
	pending map[string]*pending
}

func intSetting(cfg map[string]string, key string, def, lo, hi int64) (int64, error) {
	raw, ok := cfg[key]
	if !ok {
		return def, nil
	}
	v, err := strconv.ParseInt(strings.TrimSpace(raw), 10, 64)
	if err != nil || v < lo || v > hi {
		return 0, fmt.Errorf("%s must be a number in %d..%d: %w", key, lo, hi, ErrInvalidConfig)
	}
	return v, nil
}

// NewService reads and validates the configuration.
func NewService(cfg map[string]string, clock Clock, users UserStore, codes func(int64) int64) (*Service, error) {
	if clock == nil || users == nil || codes == nil {
		return nil, fmt.Errorf("clock, users and codes are required: %w", ErrInvalidConfig)
	}
	ttl, err := intSetting(cfg, "reset.ttl.minutes", 15, 1, 10_000_000)
	if err != nil {
		return nil, err
	}
	length, err := intSetting(cfg, "reset.code.length", 6, 4, 10)
	if err != nil {
		return nil, err
	}
	offset, err := intSetting(cfg, "reset.utc.offset.minutes", 0, -720, 840)
	if err != nil {
		return nil, err
	}
	return &Service{clock: clock, users: users, codes: codes, ttl: ttl * 60_000, length: int(length),
		offset: int(offset), pending: map[string]*pending{}}, nil
}

func (s *Service) RequestReset(email string) (string, error) {
	if !s.users.Exists(email) {
		return "", fmt.Errorf("%s: %w", email, ErrUnknownUser)
	}
	bound := int64(1)
	for i := 0; i < s.length; i++ {
		bound *= 10
	}
	code := fmt.Sprintf("%0*d", s.length, s.codes(bound))
	s.mu.Lock()
	s.pending[email] = &pending{code: code, expiresAt: s.clock.NowMillis() + s.ttl}
	s.mu.Unlock()
	return code, nil
}

func (s *Service) ExpiresAt(email string) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	p, ok := s.pending[email]
	if !ok {
		return 0, ErrNoPendingReset
	}
	return p.expiresAt, nil
}

func (s *Service) DescribeExpiry(email string) (string, error) {
	at, err := s.ExpiresAt(email)
	if err != nil {
		return "", err
	}
	zone := time.FixedZone("", s.offset*60)
	return time.UnixMilli(at).In(zone).Format("2006-01-02 15:04") + " " + label(s.offset), nil
}

func (s *Service) ConfirmReset(email, code, newPassword string) (bool, error) {
	if len(newPassword) < 8 {
		return false, ErrWeakPassword
	}
	s.mu.Lock()
	p, ok := s.pending[email]
	if !ok {
		s.mu.Unlock()
		return false, nil
	}
	if s.clock.NowMillis() >= p.expiresAt {
		delete(s.pending, email)
		s.mu.Unlock()
		return false, nil
	}
	if subtle.ConstantTimeCompare([]byte(p.code), []byte(code)) != 1 {
		p.failures++
		if p.failures >= maxAttempts {
			delete(s.pending, email)
		}
		s.mu.Unlock()
		return false, nil
	}
	delete(s.pending, email) // consumed
	s.mu.Unlock()
	if err := s.users.SetPasswordHash(email, HashPassword(newPassword)); err != nil {
		return false, err
	}
	return true, nil
}

func label(offset int) string {
	if offset == 0 {
		return "UTC"
	}
	sign := "+"
	if offset < 0 {
		sign, offset = "-", -offset
	}
	return fmt.Sprintf("UTC%s%02d:%02d", sign, offset/60, offset%60)
}
