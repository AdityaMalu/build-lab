package flags

import (
	"fmt"
	"strings"
	"sync"
)

// Bucket is 32-bit FNV-1a of key, modulo buckets.
func Bucket(key string, buckets uint32) uint32 {
	h := uint32(0x811C9DC5)
	for i := 0; i < len(key); i++ {
		h ^= uint32(key[i])
		h *= 0x01000193
	}
	return h % buckets
}

// Service holds flag definitions. Flags are stored as private copies, so a reader never sees a half-built
// flag and callers can't change one after defining it.
type Service struct {
	mu    sync.RWMutex
	flags map[string]Flag
}

// NewService creates an empty service.
func NewService() *Service {
	return &Service{flags: map[string]Flag{}}
}

func (s *Service) Define(f Flag) error {
	if strings.TrimSpace(f.Key) == "" {
		return fmt.Errorf("key required: %w", ErrInvalidFlag)
	}
	if f.RolloutPercent < 0 || f.RolloutPercent > 100 {
		return fmt.Errorf("rollout must be 0..100: %w", ErrInvalidFlag)
	}
	sum := 0
	for _, v := range f.Variants {
		if v.Weight < 0 {
			return fmt.Errorf("negative weight: %w", ErrInvalidFlag)
		}
		sum += v.Weight
	}
	if len(f.Variants) > 0 && sum != 100 {
		return fmt.Errorf("weights must sum to 100: %w", ErrInvalidFlag)
	}
	snapshot := Flag{
		Key: f.Key, Enabled: f.Enabled, RolloutPercent: f.RolloutPercent,
		Rules:    append([]Rule(nil), f.Rules...),
		Variants: append([]Variant(nil), f.Variants...),
	}
	s.mu.Lock()
	s.flags[f.Key] = snapshot
	s.mu.Unlock()
	return nil
}

func (s *Service) Evaluate(flagKey string, u User) (string, error) {
	s.mu.RLock()
	f, ok := s.flags[flagKey]
	s.mu.RUnlock()
	if !ok {
		return "", fmt.Errorf("%q: %w", flagKey, ErrUnknownFlag)
	}
	if strings.TrimSpace(u.ID) == "" {
		return "", fmt.Errorf("user id required: %w", ErrInvalidUser)
	}
	if !f.Enabled {
		return "off", nil
	}
	for _, r := range f.Rules {
		if v, ok := u.Attributes[r.Attribute]; ok && v == r.EqualsValue {
			return r.Variant, nil
		}
	}
	if int(Bucket(flagKey+":"+u.ID, 100)) >= f.RolloutPercent {
		return "off", nil
	}
	if len(f.Variants) == 0 {
		return "on", nil
	}
	b := int(Bucket(flagKey+":variant:"+u.ID, 100))
	cumulative := 0
	for _, v := range f.Variants {
		cumulative += v.Weight
		if b < cumulative {
			return v.Name, nil
		}
	}
	return "", fmt.Errorf("weights did not cover bucket %d", b)
}
