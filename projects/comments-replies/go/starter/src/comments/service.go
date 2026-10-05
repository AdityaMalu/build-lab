package comments

import "errors"

// Service stores threaded comments.
type Service struct {
	// TODO: fields
}

// NewService creates an empty service.
func NewService() *Service {
	return &Service{}
}

func (s *Service) AddComment(postID, author, text string) (Comment, error) {
	return Comment{}, errors.New("TODO")
}

func (s *Service) Reply(parentID, author, text string) (Comment, error) {
	return Comment{}, errors.New("TODO")
}

func (s *Service) Thread(postID string) []Node {
	// TODO
	return nil
}

func (s *Service) Delete(commentID, requester string) error {
	return errors.New("TODO")
}

func (s *Service) CountVisible(postID string) int {
	// TODO
	return -1
}
