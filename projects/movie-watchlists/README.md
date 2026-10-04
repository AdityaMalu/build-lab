# Persistent Private Watchlists

**Scenario.** Users can keep private watchlists ("Weekend", "Watch with kids"...). The service persists to a local
data file so lists survive restarts. Bug reports:
- a user could open **someone else's** list by guessing its id,
- adding the same movie twice shows it twice,
- deleted lists **come back** after a restart,
- after a restart, creating a new list **overwrote** an old one,
- a screen that sorted a returned list accidentally reordered the stored list.

Fix the service in your workspace.

## Contract (package `watchlists`)
`WatchlistService(Path dataFile)` loads the file if it exists (a missing file means empty state).

| Method | Behaviour |
|---|---|
| `String createList(owner, name)` | Owner non-blank. Name trimmed, 1–60 chars, may not contain `\|` or line breaks (`IllegalArgumentException`). Same owner can't have two lists with the same name, ignoring case (`IllegalStateException`). Ids `"W1"`, `"W2"`, ... and numbering **continues after a restart**. |
| `boolean addMovie(requester, listId, movieId)` | `movieId` matches `[A-Za-z0-9_-]+`. Returns `false` (and changes nothing) if already present. Insertion order is kept. |
| `boolean removeMovie(requester, listId, movieId)` | `false` if it wasn't there. |
| `Watchlist getList(requester, listId)` | A snapshot with an **unmodifiable** movie list. |
| `List<Watchlist> listsOf(owner)` | That owner's lists sorted by id number. |
| `void deleteList(requester, listId)` | Removes it permanently. |

- Unknown list → `NoSuchElementException`. Requester isn't the owner → `SecurityException` (for **every** method
  that takes a requester, reads included).
- **Every successful mutation is persisted before the method returns**, so a new `WatchlistService` on the same file
  sees exactly the same state. Failed or no-op calls don't need to write.
- Write **atomically**: write a temp file in the same directory, then move it over the data file
  (`StandardCopyOption.ATOMIC_MOVE`). A crash mid-write must never leave a half-written data file.
- Thread-safe.

The file format is one line per list: `id|owner|name|movie1,movie2,...`. You may keep it.
