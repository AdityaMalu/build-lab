package reset

import (
	"strconv"
	"time"
)

type pending struct {
	code      string
	expiresAt int64
}

// Service issues and confirms reset codes.
type Service struct {
	clock      Clock
	users      UserStore
	codes      func(int64) int64
	ttlMinutes int64
	codeLength int
	offset     int
	pending    map[string]pending
}

// NewService reads the configuration.
func NewService(cfg map[string]string, clock Clock, users UserStore, codes func(int64) int64) (*Service, error) {
	ttl := int64(15)
	if v, ok := cfg["reset.ttl.minute"]; ok {
		ttl, _ = strconv.ParseInt(v, 10, 64)
	}
	length := 6
	if v, ok := cfg["reset.code.length"]; ok {
		length, _ = strconv.Atoi(v)
	}
	offset := 0
	if v, ok := cfg["reset.utc.offset.minutes"]; ok {
		offset, _ = strconv.Atoi(v)
	}
	return &Service{clock: clock, users: users, codes: codes, ttlMinutes: ttl, codeLength: length, offset: offset,
		pending: map[string]pending{}}, nil
}

func (s *Service) RequestReset(email string) (string, error) {
	if !s.users.Exists(email) {
		return "", ErrUnknownUser
	}
	bound := int64(1)
	for i := 0; i < s.codeLength; i++ {
		bound *= 10
	}
	code := strconv.FormatInt(s.codes(bound), 10)
	expires := time.Now().UnixMilli() + s.ttlMinutes*60_000
	s.pending[email] = pending{code, expires}
	return code, nil
}

func (s *Service) ExpiresAt(email string) (int64, error) {
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
	return time.UnixMilli(at).UTC().Format("2006-01-02 15:04") + " UTC", nil
}

func (s *Service) ConfirmReset(email, code, newPassword string) (bool, error) {
	p, ok := s.pending[email]
	if !ok {
		return false, nil
	}
	if s.clock.NowMillis() > p.expiresAt {
		return false, nil
	}
	if p.code != code {
		return false, nil
	}
	if len(newPassword) < 8 {
		return false, ErrWeakPassword
	}
	return true, s.users.SetPasswordHash(email, newPassword)
}
