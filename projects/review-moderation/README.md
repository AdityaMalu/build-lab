# Comment Moderation

**Scenario.** The community team's moderation queue is unreliable: harmless comments like *"great classic"* get
auto-rejected, obvious ones like *"SPAM link"* slip through, a moderator approved a comment that had already been
rejected, and when the notification webhook is down, approvals silently vanish. Fix the service.

## Contract (package `moderation`)

`ModerationService(Set<String> bannedWords)`

| Method | Behaviour |
|---|---|
| `Submission submit(String author, String text)` | Blank author → `IllegalArgumentException`. Text is trimmed. If the trimmed text is empty or longer than **1000** chars → stored as `REJECTED` with reason `"invalid length"`. If it contains a banned word → `REJECTED` with reason `"banned word"`. Otherwise `PENDING`. Auto-rejections have `moderator = "auto"`. Ids `"S1"`, `"S2"`, ... unique under concurrency. |
| `Submission approve(String id, String moderator)` | Only from `PENDING` → `APPROVED` (else `IllegalStateException`). Unknown id → `NoSuchElementException`. |
| `Submission reject(String id, String moderator, String reason)` | Only from `PENDING` → `REJECTED`. |
| `List<Submission> byStatus(Status s)` | Ordered by submission order (S1, S2, ... numerically, so S10 comes after S9). |
| `void addListener(ModerationListener l)` | Called after every approve/reject decision by a human moderator (not for auto-rejections). |
| `int failedNotifications()` | Number of listener calls that threw. |

### Banned-word matching
- Case-insensitive.
- **Whole words only**: a word is a maximal run of letters/digits. `"ass"` is banned → `"class"` and `"assess"` are
  fine; `"ASS!"` and `"you ass."` are not.

### Failure isolation
A listener that throws must **not** undo or block the decision and must not prevent other listeners from being called.
The decision is saved first, then listeners are notified.

### Concurrency
Two moderators racing to approve/reject the same submission: exactly one wins, the other gets `IllegalStateException`.
