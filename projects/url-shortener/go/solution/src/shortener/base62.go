package shortener

import (
	"errors"
	"fmt"
	"math"
	"strings"
)

// Alphabet: digits, then upper case, then lower case.
const Alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

// ErrInvalidInput is returned for negative numbers and malformed codes.
var ErrInvalidInput = errors.New("invalid input")

// Encode converts n >= 0 to Base62.
func Encode(n int64) (string, error) {
	if n < 0 {
		return "", fmt.Errorf("negative number %d: %w", n, ErrInvalidInput)
	}
	if n == 0 {
		return "0", nil
	}
	var buf [11]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = Alphabet[n%62]
		n /= 62
	}
	return string(buf[i:]), nil
}

// Decode converts a Base62 string back to a number.
func Decode(s string) (int64, error) {
	if s == "" {
		return 0, fmt.Errorf("empty code: %w", ErrInvalidInput)
	}
	var n int64
	for _, ch := range s {
		d := strings.IndexRune(Alphabet, ch)
		if d < 0 {
			return 0, fmt.Errorf("invalid character %q: %w", ch, ErrInvalidInput)
		}
		if n > (math.MaxInt64-int64(d))/62 {
			return 0, fmt.Errorf("overflow: %w", ErrInvalidInput)
		}
		n = n*62 + int64(d)
	}
	return n, nil
}
