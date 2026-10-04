# Comments & Replies

**Scenario.** A blogging product already has posts. Product wants threaded comments: people comment on a
post and reply to each other. You own the data model and an in-memory `CommentService` that many request
threads call at once.

## What to build

Package `comments`. Records `Comment` and `CommentNode` are given. Implement `CommentService`:

| Method | Behaviour |
|---|---|
| `Comment addComment(String postId, String author, String text)` | Top-level comment, depth 0 |
| `Comment reply(String parentId, String author, String text)` | Reply, depth = parent depth + 1, same post as parent |
| `List<CommentNode> getThread(String postId)` | Top-level comments with nested replies |
| `void delete(String commentId, String requester)` | Soft delete (see below) |
| `int countVisible(String postId)` | Number of non-deleted comments on the post |

## Rules
1. **Ids** are `"c1"`, `"c2"`, ... in creation order, unique even under concurrency.
   `Comment.seq` is the same number as a `long`.
2. **Ordering:** at every level, siblings are ordered oldest first (by `seq`).
3. **Validation** → `IllegalArgumentException`: null/blank postId or author, text null/blank after trimming,
   text longer than **500** characters. Store text **trimmed**.
4. Replying to an unknown comment, or deleting an unknown comment → `NoSuchElementException`.
5. **Max depth is 3** (depths 0, 1, 2, 3). Replying to a depth-3 comment → `IllegalStateException`.
6. **Delete:** only the author may delete (`SecurityException` otherwise). Deleted comments stay in the tree
   so replies keep their context, but come back with `deleted = true`, `text = "[deleted]"` and `author = null`.
   Deleting twice is a no-op. Replying to a deleted comment → `IllegalStateException`.
7. `getThread` for a post with no comments returns an empty list. Returned lists must be safe for the
   caller to hold — later writes must not change a list you already returned.
8. Thread-safe.

## Hints
- `Map<String, Comment>` by id plus `Map<String, List<String>>` children-by-parent is enough.
- Records are immutable — "updating" a comment means replacing it in the map.
