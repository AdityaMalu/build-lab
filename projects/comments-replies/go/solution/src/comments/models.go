package comments

import "errors"

// Comment is an immutable snapshot of one comment.
type Comment struct {
	ID       string
	PostID   string
	ParentID string // "" for top-level comments
	Author   string // "" once deleted
	Text     string // "[deleted]" once deleted
	Seq      int64
	Depth    int
	Deleted  bool
}

// Node is a comment with its replies.
type Node struct {
	Comment Comment
	Replies []Node
}

const (
	MaxLength = 500
	MaxDepth  = 3
)

var (
	ErrInvalidArgument = errors.New("invalid argument")
	ErrNotFound        = errors.New("comment not found")
	ErrForbidden       = errors.New("forbidden")
	ErrInvalidState    = errors.New("invalid state")
)
