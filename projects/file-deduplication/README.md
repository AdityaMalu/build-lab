# File Deduplication

**Scenario.** A storage team wants to reclaim space on a shared drive full of copied folders. Build the scanner
that finds groups of files with **byte-identical content**. It must be fast on large trees, correct even if the
hash function collides, and must not abort because one file is unreadable.

## What to build

Package `dedup`.

```java
public interface ContentHasher { String hash(Path file) throws IOException; }   // given
public class Sha256Hasher implements ContentHasher { ... }                      // TODO
public class Deduplicator {
    public Deduplicator(ContentHasher hasher, int threads)
    public DedupReport scan(Path root) throws IOException
}
public record DedupReport(List<List<Path>> duplicateGroups, Map<Path, String> failures, long reclaimableBytes)
```

## Rules
1. Walk `root` recursively. Consider **regular files only** (skip directories; don't follow symlinks).
2. **Pipeline:** group by file size → within same-size groups, hash with the `ContentHasher` → within same-hash
   groups, **confirm with an exact byte comparison**. Two files are duplicates only if their bytes are identical.
   (Tests plug in a hasher that returns the same hash for everything; you must still get the right answer.)
3. Files whose size is unique are never hashed (tests count hasher calls).
4. Empty files: all zero-length files form one duplicate group (if there are 2+).
5. **Failures:** if hashing or reading a file throws, record `path → exception message` in `failures` and carry on
   with the rest. A failed file never appears in a duplicate group.
6. **Output ordering** (so results are deterministic): every group is sorted by path; groups are sorted by their
   first path. Only groups of size ≥ 2 are returned.
7. `reclaimableBytes` = for each group, `size × (groupSize − 1)`.
8. Hash files **concurrently** using `threads` worker threads (`threads < 1` → `IllegalArgumentException`).
   Shut your executor down before returning.
9. `Sha256Hasher` returns the lowercase hex SHA-256 of the file contents, streaming (don't load huge files fully
   into memory).

## Hints
- `Files.walk` + `Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)`.
- `Files.mismatch(a, b) == -1` compares two files byte by byte (Java 12+).
- Exact comparison among N same-hash files: pick the first unassigned file as a representative and compare the
  rest against it; repeat with what's left.
