# Comments & Replies (Python)

**Scenario.** A blogging product already has posts. Product wants threaded comments: people comment on a
post and reply to each other. You own the data model and an in-memory `CommentService` that many request
threads call at once.

## What to build (package `comments`)
Frozen dataclasses `Comment(id, post_id, parent_id, author, text, seq, depth, deleted)` and
`CommentNode(comment, replies)` are given (`replies` is a tuple). Implement `CommentService`:

| Method | Behaviour |
|---|---|
| `add_comment(post_id, author, text) -> Comment` | Top-level comment, depth 0, `parent_id=None` |
| `reply(parent_id, author, text) -> Comment` | Depth = parent depth + 1, same post as the parent |
| `get_thread(post_id) -> list[CommentNode]` | Top-level comments with nested replies |
| `delete(comment_id, requester)` | Soft delete (see below) |
| `count_visible(post_id) -> int` | Non-deleted comments on the post |

## Rules
1. **Ids** are `"c1"`, `"c2"`, ... in creation order, unique under concurrency; `seq` is the same number as an int.
2. **Ordering:** at every level, siblings are oldest first (by `seq`).
3. **Validation** → `ValueError`: blank `post_id` or `author`, text `None`/blank after stripping, or longer than
   **500** characters. Store the text **stripped**.
4. Replying to or deleting an unknown comment → `KeyError`.
5. **Max depth is 3.** Replying to a depth-3 comment → `RuntimeError`.
6. **Delete:** only the author may delete (`PermissionError`). The comment stays in the tree with
   `deleted=True`, `text="[deleted]"` and `author=None`. Deleting twice is a no-op. Replying to a deleted comment
   → `RuntimeError`.
7. `get_thread` returns a snapshot: later writes must not change what you already returned.
8. Thread-safe.
