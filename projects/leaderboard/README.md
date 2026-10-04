# Leaderboard Service

**Scenario.** A game's season leaderboard is live and the support queue is filling up:
- *"I scored lower in a later match and my best score went **down**."*
- *"Two of us tied for 2nd, but the next player is shown as 3rd. Shouldn't they be 4th?"*
- *"I hit 900 first, yet the other player with 900 is listed above me."*
- *"After I improved my score I appear **twice** on the board."*
- *"I deleted my account but I'm still on the leaderboard."*
- *"The 'players around me' view crashes when I'm near the top"*, and *"page 1 skips the top players"*.
- Under load, some players' best scores are lost.

The service in your workspace is production code. Fix it.

## Contract (package `leaderboard`)
`RankedEntry(rank, player, score)` and `Page(pageNumber, pageSize, totalPlayers, entries)` are given.

| Method | Behaviour |
|---|---|
| `boolean submitScore(player, score)` | Keeps each player's **best** score. Returns `true` if this score became their new best (strictly higher, or first score). Blank player or negative score → `IllegalArgumentException`. |
| `List<RankedEntry> top(n)` | The first `n` entries (all of them if fewer). `n < 0` → `IllegalArgumentException`. |
| `int rankOf(player)` | Their rank. Unknown → `NoSuchElementException`. |
| `List<RankedEntry> around(player, k)` | Up to `k` entries above and `k` below the player, plus the player, clipped at both ends. `k < 0` → `IllegalArgumentException`. |
| `Page page(pageNumber, pageSize)` | `pageNumber` starts at **1**. `pageSize` 1–100. Out-of-range pages are empty. |
| `boolean removePlayer(player)` | Removes them everywhere. `false` if unknown. |
| `int size()` | Number of players. |

### Ordering and ranks
- Order by score (highest first). Equal scores: whoever **reached that score first** comes first. Improving your score
  counts as reaching the new score at that moment. Then by player name, as a last resort.
- **Competition ranking ("1224")**: players with equal scores share a rank, and the next rank skips accordingly.
  Scores 100, 90, 90, 80 → ranks 1, 2, 2, 4.

Thread-safe: many score submissions arrive concurrently.

## Debugging hints
- Look closely at what happens to an object that is **inside a `TreeSet`** when one of its sort fields changes.
- Read each index calculation in `around` and `page` with a small example on paper.
