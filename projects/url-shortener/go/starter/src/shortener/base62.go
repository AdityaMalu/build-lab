package shortener

import "errors"

// Alphabet: digits, then upper case, then lower case.
const Alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

// ErrInvalidInput is returned for negative numbers and malformed codes.
var ErrInvalidInput = errors.New("invalid input")

// Encode converts n >= 0 to Base62.
func Encode(n int64) (string, error) {
	// TODO
	return "", errors.New("TODO")
}

// Decode converts a Base62 string back to a number.
func Decode(s string) (int64, error) {
	// TODO
	return 0, errors.New("TODO")
}
