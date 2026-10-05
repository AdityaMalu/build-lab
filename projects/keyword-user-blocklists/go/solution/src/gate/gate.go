package gate

import (
	"fmt"
	"math"
	"strings"
	"sync"
	"unicode"
)

type userState struct {
	mu           sync.Mutex
	blockedUntil int64 // math.MinInt64 = not blocked, math.MaxInt64 = permanent
	strikes      []int64
}

// Gate checks submissions against keywords and per-user blocks.
type Gate struct {
	clock    Clock
	kwMu     sync.RWMutex
	keywords map[string]bool // normalized phrase: tokens joined by one space
	usersMu  sync.Mutex
	users    map[string]*userState
}

// NewGate creates a gate.
func NewGate(clock Clock) *Gate {
	return &Gate{clock: clock, keywords: map[string]bool{}, users: map[string]*userState{}}
}

func tokens(text string) []string {
	return strings.FieldsFunc(strings.ToLower(text), func(r rune) bool {
		return !unicode.IsLetter(r) && !unicode.IsDigit(r)
	})
}

func normalize(phrase string) (string, error) {
	t := tokens(phrase)
	if len(t) == 0 {
		return "", fmt.Errorf("phrase has no words: %w", ErrInvalidArgument)
	}
	return strings.Join(t, " "), nil
}

func (g *Gate) AddKeyword(phrase string) error {
	k, err := normalize(phrase)
	if err != nil {
		return err
	}
	g.kwMu.Lock()
	g.keywords[k] = true
	g.kwMu.Unlock()
	return nil
}

func (g *Gate) RemoveKeyword(phrase string) error {
	k, err := normalize(phrase)
	if err != nil {
		return err
	}
	g.kwMu.Lock()
	delete(g.keywords, k)
	g.kwMu.Unlock()
	return nil
}

func (g *Gate) BlockUser(userID string, durationMillis int64) error {
	s, err := g.state(userID)
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if durationMillis <= 0 {
		s.blockedUntil = math.MaxInt64
	} else {
		s.blockedUntil = g.clock.NowMillis() + durationMillis
	}
	return nil
}

func (g *Gate) UnblockUser(userID string) error {
	s, err := g.state(userID)
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.blockedUntil = math.MinInt64
	s.strikes = nil
	return nil
}

func (g *Gate) IsBlocked(userID string) bool {
	s, err := g.state(userID)
	if err != nil {
		return false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return g.clock.NowMillis() < s.blockedUntil
}

func (g *Gate) Submit(userID, text string) (Decision, error) {
	s, err := g.state(userID)
	if err != nil {
		return "", err
	}
	violates := g.matches(tokens(text)) // pure: no user lock needed
	s.mu.Lock()
	defer s.mu.Unlock()
	now := g.clock.NowMillis()
	if now < s.blockedUntil {
		return BlockedUser, nil
	}
	if !violates {
		return Accepted, nil
	}
	prune(s, now)
	s.strikes = append(s.strikes, now)
	if len(s.strikes) >= StrikeLimit {
		s.blockedUntil = now + AutoBlockMillis
		s.strikes = nil
	}
	return RejectedKeyword, nil
}

func (g *Gate) ActiveStrikes(userID string) int {
	s, err := g.state(userID)
	if err != nil {
		return 0
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	prune(s, g.clock.NowMillis())
	return len(s.strikes)
}

func (g *Gate) matches(textTokens []string) bool {
	if len(textTokens) == 0 {
		return false
	}
	joined := " " + strings.Join(textTokens, " ") + " "
	g.kwMu.RLock()
	defer g.kwMu.RUnlock()
	for k := range g.keywords {
		if strings.Contains(joined, " "+k+" ") {
			return true
		}
	}
	return false
}

func prune(s *userState, now int64) {
	i := 0
	for i < len(s.strikes) && s.strikes[i]+StrikeWindowMillis <= now {
		i++
	}
	s.strikes = s.strikes[i:]
}

func (g *Gate) state(userID string) (*userState, error) {
	if strings.TrimSpace(userID) == "" {
		return nil, fmt.Errorf("user id required: %w", ErrInvalidArgument)
	}
	g.usersMu.Lock()
	defer g.usersMu.Unlock()
	s, ok := g.users[userID]
	if !ok {
		s = &userState{blockedUntil: math.MinInt64}
		g.users[userID] = s
	}
	return s, nil
}
