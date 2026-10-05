package comments

import (
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
)

// must(t)(s.AddComment(...)) fails the test on error and returns the comment.
func must(t *testing.T) func(Comment, error) Comment {
	t.Helper()
	return func(c Comment, err error) Comment {
		t.Helper()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		return c
	}
}

// Test: top-level comment gets id c1, depth 0, trimmed text
func TestAddComment(t *testing.T) {
	s := NewService()
	c := must(t)(s.AddComment("p1", "ana", "  hello world  "))
	if c.ID != "c1" || c.Seq != 1 || c.PostID != "p1" || c.ParentID != "" || c.Depth != 0 || c.Deleted {
		t.Fatalf("unexpected comment %+v", c)
	}
	if c.Text != "hello world" {
		t.Fatalf("text must be trimmed, got %q", c.Text)
	}
}

// Test: reply inherits post and increments depth
func TestReplyDepth(t *testing.T) {
	s := NewService()
	root := must(t)(s.AddComment("p1", "ana", "root"))
	r1 := must(t)(s.Reply(root.ID, "ben", "r1"))
	r2 := must(t)(s.Reply(r1.ID, "ana", "r2"))
	if r2.PostID != "p1" || r2.ParentID != r1.ID || r2.Depth != 2 {
		t.Fatalf("unexpected reply %+v", r2)
	}
}

// Test: thread is nested and ordered oldest first at every level
func TestThreadShape(t *testing.T) {
	s := NewService()
	a := must(t)(s.AddComment("p1", "u", "A"))
	b := must(t)(s.AddComment("p1", "u", "B"))
	must(t)(s.AddComment("p2", "u", "other post"))
	a1 := must(t)(s.Reply(a.ID, "u", "A1"))
	b1 := must(t)(s.Reply(b.ID, "u", "B1"))
	a2 := must(t)(s.Reply(a.ID, "u", "A2"))
	a1x := must(t)(s.Reply(a1.ID, "u", "A1x"))
	th := s.Thread("p1")
	if len(th) != 2 || th[0].Comment.Text != "A" || th[1].Comment.Text != "B" {
		t.Fatalf("top level wrong: %+v", th)
	}
	if len(th[0].Replies) != 2 || th[0].Replies[0].Comment.ID != a1.ID || th[0].Replies[1].Comment.ID != a2.ID {
		t.Fatalf("A's replies wrong: %+v", th[0].Replies)
	}
	if th[0].Replies[0].Replies[0].Comment.ID != a1x.ID || th[1].Replies[0].Comment.ID != b1.ID {
		t.Fatal("nested replies wrong")
	}
	if len(s.Thread("nobody")) != 0 {
		t.Fatal("unknown post gives empty thread")
	}
}

// Test: validation errors
func TestValidation(t *testing.T) {
	s := NewService()
	cases := []struct{ post, author, text string }{
		{"", "u", "x"}, {"p", " ", "x"}, {"p", "u", "   "}, {"p", "u", strings.Repeat("x", 501)},
	}
	for _, c := range cases {
		if _, err := s.AddComment(c.post, c.author, c.text); !errors.Is(err, ErrInvalidArgument) {
			t.Fatalf("AddComment(%q, %q, %.10q): want ErrInvalidArgument, got %v", c.post, c.author, c.text, err)
		}
	}
	ok := must(t)(s.AddComment("p", "u", "  "+strings.Repeat("x", 500)+"  "))
	if len(ok.Text) != 500 {
		t.Fatalf("500 chars after trimming is allowed, got %d", len(ok.Text))
	}
	if _, err := s.Reply("c999", "u", "x"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown parent: want ErrNotFound, got %v", err)
	}
}

// Test: max depth is 3
func TestMaxDepth(t *testing.T) {
	s := NewService()
	c := must(t)(s.AddComment("p", "u", "d0"))
	for d := 1; d <= 3; d++ {
		c = must(t)(s.Reply(c.ID, "u", fmt.Sprintf("d%d", d)))
	}
	if c.Depth != 3 {
		t.Fatalf("depth: want 3, got %d", c.Depth)
	}
	if _, err := s.Reply(c.ID, "u", "too deep"); !errors.Is(err, ErrInvalidState) {
		t.Fatalf("want ErrInvalidState, got %v", err)
	}
}

// Test: soft delete keeps the node, hides author and text
func TestSoftDelete(t *testing.T) {
	s := NewService()
	root := must(t)(s.AddComment("p", "ana", "secret"))
	must(t)(s.Reply(root.ID, "ben", "child"))
	if err := s.Delete(root.ID, "ana"); err != nil {
		t.Fatal(err)
	}
	n := s.Thread("p")[0]
	if !n.Comment.Deleted || n.Comment.Text != "[deleted]" || n.Comment.Author != "" {
		t.Fatalf("deleted comment wrong: %+v", n.Comment)
	}
	if len(n.Replies) != 1 {
		t.Fatal("replies survive")
	}
	if s.CountVisible("p") != 1 {
		t.Fatalf("count visible: want 1, got %d", s.CountVisible("p"))
	}
	if err := s.Delete(root.ID, "ana"); err != nil {
		t.Fatalf("second delete is a no-op, got %v", err)
	}
	if _, err := s.Reply(root.ID, "ben", "x"); !errors.Is(err, ErrInvalidState) {
		t.Fatalf("reply to deleted: want ErrInvalidState, got %v", err)
	}
}

// Test: only the author can delete
func TestDeleteAuthorization(t *testing.T) {
	s := NewService()
	c := must(t)(s.AddComment("p", "ana", "mine"))
	if err := s.Delete(c.ID, "ben"); !errors.Is(err, ErrForbidden) {
		t.Fatalf("want ErrForbidden, got %v", err)
	}
	if err := s.Delete("c42", "ana"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
	if s.CountVisible("p") != 1 {
		t.Fatal("comment must still be visible")
	}
}

// Test: returned thread is a snapshot
func TestSnapshot(t *testing.T) {
	s := NewService()
	c := must(t)(s.AddComment("p", "u", "first"))
	before := s.Thread("p")
	must(t)(s.AddComment("p", "u", "second"))
	must(t)(s.Reply(c.ID, "u", "reply"))
	if len(before) != 1 || len(before[0].Replies) != 0 {
		t.Fatal("an earlier snapshot must not change")
	}
}

// Test: concurrent writers get unique sequential ids
func TestConcurrency(t *testing.T) {
	s := NewService()
	root := must(t)(s.AddComment("p", "u", "root"))
	var mu sync.Mutex
	ids := map[string]bool{}
	var wg sync.WaitGroup
	for g := 0; g < 16; g++ {
		wg.Add(1)
		go func(g int) {
			defer wg.Done()
			for k := 0; k < 100; k++ {
				var c Comment
				var err error
				if k%2 == 0 {
					c, err = s.AddComment("p", fmt.Sprint("u", g), "t")
				} else {
					c, err = s.Reply(root.ID, fmt.Sprint("u", g), "r")
				}
				if err != nil {
					t.Error(err)
					return
				}
				mu.Lock()
				ids[c.ID] = true
				mu.Unlock()
			}
		}(g)
	}
	wg.Wait()
	if len(ids) != 1600 {
		t.Fatalf("all ids unique: want 1600, got %d", len(ids))
	}
	for n := 2; n <= 1601; n++ {
		if !ids[fmt.Sprintf("c%d", n)] {
			t.Fatalf("missing id c%d", n)
		}
	}
	if s.CountVisible("p") != 1601 || len(s.Thread("p")[0].Replies) != 800 {
		t.Fatal("counts wrong after concurrent writes")
	}
}
