package comments

import (
	"fmt"
	"strings"
	"sync"
	"unicode/utf8"
)

// Service stores threaded comments.
type Service struct {
	mu       sync.Mutex
	byID     map[string]Comment
	children map[string][]string // parent id, or "\x00root:"+postID for top level
	nextSeq  int64
}

// NewService creates an empty service.
func NewService() *Service {
	return &Service{byID: map[string]Comment{}, children: map[string][]string{}, nextSeq: 1}
}

func rootKey(postID string) string { return "\x00root:" + postID }

func clean(text string) (string, error) {
	t := strings.TrimSpace(text)
	if t == "" {
		return "", fmt.Errorf("text required: %w", ErrInvalidArgument)
	}
	if utf8.RuneCountInString(t) > MaxLength {
		return "", fmt.Errorf("text too long: %w", ErrInvalidArgument)
	}
	return t, nil
}

func (s *Service) AddComment(postID, author, text string) (Comment, error) {
	if strings.TrimSpace(postID) == "" || strings.TrimSpace(author) == "" {
		return Comment{}, fmt.Errorf("post and author required: %w", ErrInvalidArgument)
	}
	body, err := clean(text)
	if err != nil {
		return Comment{}, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.insert(postID, "", author, body, 0), nil
}

func (s *Service) Reply(parentID, author, text string) (Comment, error) {
	if strings.TrimSpace(author) == "" {
		return Comment{}, fmt.Errorf("author required: %w", ErrInvalidArgument)
	}
	body, err := clean(text)
	if err != nil {
		return Comment{}, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	parent, ok := s.byID[parentID]
	switch {
	case !ok:
		return Comment{}, fmt.Errorf("%s: %w", parentID, ErrNotFound)
	case parent.Deleted:
		return Comment{}, fmt.Errorf("cannot reply to a deleted comment: %w", ErrInvalidState)
	case parent.Depth >= MaxDepth:
		return Comment{}, fmt.Errorf("max depth reached: %w", ErrInvalidState)
	}
	return s.insert(parent.PostID, parent.ID, author, body, parent.Depth+1), nil
}

func (s *Service) Thread(postID string) []Node {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.build(rootKey(postID))
}

func (s *Service) Delete(commentID, requester string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	c, ok := s.byID[commentID]
	switch {
	case !ok:
		return fmt.Errorf("%s: %w", commentID, ErrNotFound)
	case c.Deleted:
		return nil
	case c.Author != requester:
		return fmt.Errorf("only the author may delete: %w", ErrForbidden)
	}
	c.Author, c.Text, c.Deleted = "", "[deleted]", true
	s.byID[commentID] = c
	return nil
}

func (s *Service) CountVisible(postID string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	n := 0
	for _, c := range s.byID {
		if c.PostID == postID && !c.Deleted {
			n++
		}
	}
	return n
}

func (s *Service) insert(postID, parentID, author, body string, depth int) Comment {
	seq := s.nextSeq
	s.nextSeq++
	c := Comment{ID: fmt.Sprintf("c%d", seq), PostID: postID, ParentID: parentID, Author: author, Text: body,
		Seq: seq, Depth: depth}
	s.byID[c.ID] = c
	key := parentID
	if key == "" {
		key = rootKey(postID)
	}
	s.children[key] = append(s.children[key], c.ID)
	return c
}

// build copies everything, so callers never share slices with the service.
func (s *Service) build(key string) []Node {
	ids := s.children[key]
	out := make([]Node, 0, len(ids))
	for _, id := range ids {
		out = append(out, Node{Comment: s.byID[id], Replies: s.build(id)})
	}
	return out
}
