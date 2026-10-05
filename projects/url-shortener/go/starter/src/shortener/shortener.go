package shortener

import "errors"

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

// Shortener creates and resolves short links.
type Shortener struct {
	// TODO: fields
}

// NewShortener creates an empty shortener.
func NewShortener(clock Clock) *Shortener {
	// TODO
	return &Shortener{}
}

func (s *Shortener) Shorten(longURL string, ttlMillis int64) (string, error) {
	return "", errors.New("TODO")
}

func (s *Shortener) ShortenWithAlias(longURL, alias string, ttlMillis int64) (string, error) {
	return "", errors.New("TODO")
}

func (s *Shortener) Resolve(code string) (string, bool) {
	// TODO
	return "", false
}

func (s *Shortener) Stats(code string) (LinkStats, error) {
	return LinkStats{}, errors.New("TODO")
}
