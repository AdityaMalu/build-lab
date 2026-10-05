package flags

import "errors"

// Bucket is 32-bit FNV-1a of key, modulo buckets.
func Bucket(key string, buckets uint32) uint32 {
	// TODO
	return 0
}

// Service holds flag definitions.
type Service struct {
	// TODO: fields
}

// NewService creates an empty service.
func NewService() *Service {
	return &Service{}
}

func (s *Service) Define(f Flag) error {
	return errors.New("TODO")
}

func (s *Service) Evaluate(flagKey string, u User) (string, error) {
	return "", errors.New("TODO")
}
