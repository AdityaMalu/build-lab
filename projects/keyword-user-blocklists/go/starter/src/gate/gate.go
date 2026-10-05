package gate

import "errors"

// Gate checks submissions against keywords and per-user blocks.
type Gate struct {
	// TODO: fields
}

// NewGate creates a gate.
func NewGate(clock Clock) *Gate {
	return &Gate{}
}

func (g *Gate) AddKeyword(phrase string) error    { return errors.New("TODO") }
func (g *Gate) RemoveKeyword(phrase string) error { return errors.New("TODO") }

func (g *Gate) BlockUser(userID string, durationMillis int64) error { return errors.New("TODO") }
func (g *Gate) UnblockUser(userID string) error                     { return errors.New("TODO") }
func (g *Gate) IsBlocked(userID string) bool                        { return false }

func (g *Gate) Submit(userID, text string) (Decision, error) {
	return "", errors.New("TODO")
}

func (g *Gate) ActiveStrikes(userID string) int { return -1 }
