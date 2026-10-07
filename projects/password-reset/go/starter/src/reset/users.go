package reset

// Given. Password hashing uses only the standard library so the exercise has no dependencies.
// In production use a slow, salted KDF (bcrypt / scrypt / Argon2) with a per-user salt.

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"sync"
)

// Clock is injected so tests can control time.
type Clock interface {
	NowMillis() int64
}

// UserStore holds password hashes.
type UserStore interface {
	Exists(email string) bool
	SetPasswordHash(email, hash string) error
	PasswordHash(email string) string
}

var (
	ErrInvalidConfig  = errors.New("invalid configuration")
	ErrUnknownUser    = errors.New("unknown user")
	ErrNoPendingReset = errors.New("no pending reset")
	ErrWeakPassword   = errors.New("password too short")
)

// HashPassword returns the stored form of a password: PBKDF2-HMAC-SHA256 (RFC 8018), standard library only.
// Demo settings: a fixed salt keeps it deterministic for the tests. Real code uses a random per-user salt
// stored next to the hash (or bcrypt / Argon2) and far more iterations.
func HashPassword(password string) string {
	const iterations = 20_000
	mac := hmac.New(sha256.New, []byte(password))
	mac.Write([]byte("practice-lab"))
	mac.Write([]byte{0, 0, 0, 1}) // block 1: SHA-256 output is already the 32 bytes we keep
	u := mac.Sum(nil)
	out := append([]byte(nil), u...)
	for i := 1; i < iterations; i++ {
		mac.Reset()
		mac.Write(u)
		u = mac.Sum(u[:0])
		for j := range out {
			out[j] ^= u[j]
		}
	}
	return "pbkdf2-sha256:" + hex.EncodeToString(out)
}

// MemoryStore is an in-memory UserStore.
type MemoryStore struct {
	mu     sync.Mutex
	hashes map[string]string
}

// NewMemoryStore creates an empty store.
func NewMemoryStore() *MemoryStore { return &MemoryStore{hashes: map[string]string{}} }

// Add registers a user with an initial hash.
func (m *MemoryStore) Add(email, hash string) *MemoryStore {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.hashes[email] = hash
	return m
}

func (m *MemoryStore) Exists(email string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	_, ok := m.hashes[email]
	return ok
}

func (m *MemoryStore) SetPasswordHash(email, hash string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.hashes[email]; !ok {
		return ErrUnknownUser
	}
	m.hashes[email] = hash
	return nil
}

func (m *MemoryStore) PasswordHash(email string) string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.hashes[email]
}
