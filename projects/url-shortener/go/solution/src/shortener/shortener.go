package shortener

import (
	"errors"
	"fmt"
	"math"
	"regexp"
	"strings"
	"sync"
	"unicode"
)

// Clock is injected so tests can control time.
type Clock interface {
	NowMillis() int64
}

// LinkStats is a snapshot of a link's usage.
type LinkStats struct {
	Code             string
	Hits             int64
	LastAccessMillis int64
}

var (
	ErrInvalidURL   = errors.New("invalid url")
	ErrInvalidAlias = errors.New("invalid alias")
	ErrAliasTaken   = errors.New("alias taken")
	ErrNotFound     = errors.New("not found")
)

const offset = 916_132_832 // 62^5: generated codes have at least 6 digits

var aliasPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{3,32}$`)

type link struct {
	url        string
	expiresAt  int64
	hits       int64
	lastAccess int64
}

// Shortener creates and resolves short links.
type Shortener struct {
	clock     Clock
	mu        sync.Mutex
	links     map[string]*link
	generated map[string]string // url -> generated code
	counter   int64
}

// NewShortener creates an empty shortener.
func NewShortener(clock Clock) *Shortener {
	return &Shortener{clock: clock, links: map[string]*link{}, generated: map[string]string{}}
}

func (s *Shortener) Shorten(longURL string, ttlMillis int64) (string, error) {
	if err := validateURL(longURL); err != nil {
		return "", err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.clock.NowMillis()
	if code, ok := s.generated[longURL]; ok && s.links[code].expiresAt > now {
		return code, nil
	}
	var code string
	for {
		code, _ = Encode(offset + s.counter)
		s.counter++
		if _, taken := s.links[code]; !taken { // an alias may have taken it
			break
		}
	}
	s.links[code] = &link{url: longURL, expiresAt: expiry(now, ttlMillis), lastAccess: -1}
	s.generated[longURL] = code
	return code, nil
}

func (s *Shortener) ShortenWithAlias(longURL, alias string, ttlMillis int64) (string, error) {
	if err := validateURL(longURL); err != nil {
		return "", err
	}
	if !aliasPattern.MatchString(alias) {
		return "", fmt.Errorf("%q: %w", alias, ErrInvalidAlias)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, taken := s.links[alias]; taken {
		return "", fmt.Errorf("%q: %w", alias, ErrAliasTaken)
	}
	s.links[alias] = &link{url: longURL, expiresAt: expiry(s.clock.NowMillis(), ttlMillis), lastAccess: -1}
	return alias, nil
}

func (s *Shortener) Resolve(code string) (string, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	l, ok := s.links[code]
	now := s.clock.NowMillis()
	if !ok || now >= l.expiresAt {
		return "", false
	}
	l.hits++
	l.lastAccess = now
	return l.url, true
}

func (s *Shortener) Stats(code string) (LinkStats, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	l, ok := s.links[code]
	if !ok {
		return LinkStats{}, fmt.Errorf("%q: %w", code, ErrNotFound)
	}
	return LinkStats{Code: code, Hits: l.hits, LastAccessMillis: l.lastAccess}, nil
}

func expiry(now, ttl int64) int64 {
	if ttl <= 0 || now > math.MaxInt64-ttl {
		return math.MaxInt64
	}
	return now + ttl
}

func validateURL(u string) error {
	if len(u) > 2048 {
		return fmt.Errorf("too long: %w", ErrInvalidURL)
	}
	var rest string
	switch {
	case strings.HasPrefix(u, "https://"):
		rest = u[8:]
	case strings.HasPrefix(u, "http://"):
		rest = u[7:]
	default:
		return fmt.Errorf("must be http(s): %w", ErrInvalidURL)
	}
	if rest == "" {
		return fmt.Errorf("missing host: %w", ErrInvalidURL)
	}
	if strings.IndexFunc(u, unicode.IsSpace) >= 0 {
		return fmt.Errorf("whitespace: %w", ErrInvalidURL)
	}
	return nil
}
