# Comments & Replies (C++)

**Scenario.** A blogging product already has posts. Product wants threaded comments: people comment on a
post and reply to each other. You own the data model and an in-memory `CommentService` that many request
threads call at once.

## What to build (header `comments.hpp`, C++20)
`Comment{id, postId, parentId, author, text, seq, depth, deleted}` (with `std::optional` parent and author) and
`CommentNode{comment, replies}` are given. Implement `CommentService`:

| Method | Behaviour |
|---|---|
| `Comment addComment(postId, author, text)` | Top-level comment, depth 0, no parent |
| `Comment reply(parentId, author, text)` | Depth = parent depth + 1, same post as the parent |
| `std::vector<CommentNode> getThread(postId)` | Top-level comments with nested replies |
| `void remove(commentId, requester)` | Soft delete (see below) |
| `int countVisible(postId)` | Non-deleted comments on the post |

## Rules
1. **Ids** are `"c1"`, `"c2"`, ... in creation order, unique under concurrency; `seq` is the same number.
2. **Ordering:** at every level, siblings are oldest first (by `seq`).
3. **Validation** → `std::invalid_argument`: blank `postId` or `author`, text blank after trimming whitespace, or
   longer than **500** characters. Store the text **trimmed**.
4. Replying to or deleting an unknown comment → `std::out_of_range`.
5. **Max depth is 3.** Replying to a depth-3 comment → `InvalidState` (declared in the header).
6. **Delete:** only the author may delete (`PermissionDenied`, declared in the header). The comment stays in the
   tree with `deleted == true`, `text == "[deleted]"` and no author. Deleting twice is a no-op. Replying to a
   deleted comment → `InvalidState`.
7. `getThread` returns copies, so later writes can't change what you already returned.
8. Thread-safe.
