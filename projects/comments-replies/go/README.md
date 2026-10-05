# Comments & Replies (Go)

**Scenario.** A blogging product already has posts. Product wants threaded comments: people comment on a
post and reply to each other. You own the data model and an in-memory comment service that many request
goroutines call at once.

## What to build (package `comments`)
`Comment{ID, PostID, ParentID, Author, Text, Seq, Depth, Deleted}` and `Node{Comment, Replies}` are given.
Implement `Service` (create it with `NewService()`):

| Method | Behaviour |
|---|---|
| `AddComment(postID, author, text string) (Comment, error)` | Top-level comment, depth 0, `ParentID == ""` |
| `Reply(parentID, author, text string) (Comment, error)` | Depth = parent depth + 1, same post as the parent |
| `Thread(postID string) []Node` | Top-level comments with nested replies |
| `Delete(commentID, requester string) error` | Soft delete (see below) |
| `CountVisible(postID string) int` | Non-deleted comments on the post |

## Rules
1. **Ids** are `"c1"`, `"c2"`, ... in creation order, unique under concurrency; `Seq` is the same number.
2. **Ordering:** at every level, siblings are oldest first (by `Seq`).
3. **Validation** → error wrapping `ErrInvalidArgument`: blank `postID` or `author`, text blank after trimming, or
   longer than **500** characters (runes). Store the text **trimmed**.
4. Replying to or deleting an unknown comment → `ErrNotFound`.
5. **Max depth is 3.** Replying to a depth-3 comment → `ErrInvalidState`.
6. **Delete:** only the author may delete (`ErrForbidden`). The comment stays in the tree with `Deleted == true`,
   `Text == "[deleted]"` and `Author == ""`. Deleting twice is a no-op (nil error). Replying to a deleted comment
   → `ErrInvalidState`.
7. `Thread` returns a snapshot: later writes must not change slices you already returned.
8. Safe for concurrent use.
