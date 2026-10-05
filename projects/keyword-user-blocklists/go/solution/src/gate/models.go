package gate

import "errors"

// Clock is injected so tests can control time.
type Clock interface {
	NowMillis() int64
}

// Decision is the outcome of a submission.
type Decision string

const (
	Accepted        Decision = "ACCEPTED"
	RejectedKeyword Decision = "REJECTED_KEYWORD"
	BlockedUser     Decision = "BLOCKED_USER"
)

const (
	StrikeLimit        = 3
	StrikeWindowMillis = int64(60 * 60 * 1000)
	AutoBlockMillis    = int64(24 * 60 * 60 * 1000)
)

// ErrInvalidArgument is wrapped by every validation error.
var ErrInvalidArgument = errors.New("invalid argument")
