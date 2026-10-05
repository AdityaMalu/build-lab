package flags

import "errors"

// Rule: if user.Attributes[Attribute] == EqualsValue, serve Variant.
type Rule struct {
	Attribute   string
	EqualsValue string
	Variant     string
}

// Variant is one arm of an experiment.
type Variant struct {
	Name   string
	Weight int
}

// Flag is a feature flag definition.
type Flag struct {
	Key            string
	Enabled        bool
	RolloutPercent int
	Rules          []Rule
	Variants       []Variant
}

// User is who a flag is evaluated for.
type User struct {
	ID         string
	Attributes map[string]string
}

var (
	ErrInvalidFlag = errors.New("invalid flag")
	ErrUnknownFlag = errors.New("unknown flag")
	ErrInvalidUser = errors.New("invalid user")
)
