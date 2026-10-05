package dedup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import testkit.Test;

import static testkit.Assert.*;

public class DeduplicatorTest {

    static Path tempTree() throws IOException {
        return Files.createTempDirectory("dedup-test");
    }

    static Path write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    static void cleanup(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }

    @Test("sha256 of known input")
    public void sha256() throws Exception {
        Path root = tempTree();
        try {
            Path f = write(root, "a.txt", "abc");
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    new Sha256Hasher().hash(f));
            Path empty = write(root, "e.txt", "");
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    new Sha256Hasher().hash(empty));
        } finally {
            cleanup(root);
        }
    }

    @Test("finds duplicate groups across nested folders, sorted deterministically")
    public void basicGroups() throws Exception {
        Path root = tempTree();
        try {
            Path a1 = write(root, "photos/a.jpg", "AAAA");
            Path a2 = write(root, "backup/2024/a-copy.jpg", "AAAA");
            Path a3 = write(root, "z/a.jpg", "AAAA");
            Path b1 = write(root, "docs/b.txt", "hello world");
            Path b2 = write(root, "b.txt", "hello world");
            write(root, "unique.txt", "nothing like me");
            write(root, "same-size.txt", "BBBB"); // same size as AAAA, different bytes

            DedupReport r = new Deduplicator(new Sha256Hasher(), 4).scan(root);
            List<Path> groupA = List.of(a2, a1, a3).stream().sorted().toList();
            List<Path> groupB = List.of(b2, b1).stream().sorted().toList();
            List<List<Path>> expected = Stream.of(groupA, groupB).sorted(Comparator.comparing(g -> g.get(0))).toList();
            assertEquals(expected, r.duplicateGroups());
            assertEquals(4L * 2 + 11L * 1, r.reclaimableBytes(), "reclaimable bytes");
            assertTrue(r.failures().isEmpty(), "no failures expected");
        } finally {
            cleanup(root);
        }
    }

    @Test("unique sizes are never hashed")
    public void sizeFilter() throws Exception {
        Path root = tempTree();
        try {
            write(root, "1", "a");
            write(root, "2", "bb");
            write(root, "3", "ccc");
            write(root, "4", "dd");
            AtomicInteger calls = new AtomicInteger();
            ContentHasher counting = p -> {
                calls.incrementAndGet();
                return new Sha256Hasher().hash(p);
            };
            DedupReport r = new Deduplicator(counting, 2).scan(root);
            assertEquals(2, calls.get(), "only the two 2-byte files need hashing");
            assertTrue(r.duplicateGroups().isEmpty(), "bb vs dd are different");
        } finally {
            cleanup(root);
        }
    }

    @Test("hash collisions do not create false duplicates")
    public void collisionSafe() throws Exception {
        Path root = tempTree();
        try {
            Path x1 = write(root, "x1", "XXXX");
            Path x2 = write(root, "x2", "XXXX");
            Path y1 = write(root, "y1", "YYYY");
            Path y2 = write(root, "y2", "YYYY");
            write(root, "z", "ZZZZ");
            ContentHasher terrible = p -> "same-hash-for-everyone";
            DedupReport r = new Deduplicator(terrible, 3).scan(root);
            assertEquals(List.of(List.of(x1, x2), List.of(y1, y2)), r.duplicateGroups());
            assertEquals(8L, r.reclaimableBytes());
        } finally {
            cleanup(root);
        }
    }

    @Test("a failing file is reported and does not abort the scan")
    public void failureIsolation() throws Exception {
        Path root = tempTree();
        try {
            Path a = write(root, "a", "same");
            Path b = write(root, "b", "same");
            Path bad = write(root, "bad", "same");
            ContentHasher flaky = p -> {
                if (p.getFileName().toString().equals("bad")) throw new IOException("disk read error");
                return new Sha256Hasher().hash(p);
            };
            DedupReport r = new Deduplicator(flaky, 2).scan(root);
            assertEquals(List.of(List.of(a, b)), r.duplicateGroups());
            assertEquals(1, r.failures().size());
            assertTrue(r.failures().containsKey(bad), "bad file recorded");
            assertTrue(r.failures().get(bad).contains("disk read error"), "message preserved");
        } finally {
            cleanup(root);
        }
    }

    @Test("empty files form one group")
    public void emptyFiles() throws Exception {
        Path root = tempTree();
        try {
            Path e1 = write(root, "e1", "");
            Path e2 = write(root, "sub/e2", "");
            DedupReport r = new Deduplicator(new Sha256Hasher(), 1).scan(root);
            assertEquals(List.of(List.of(e1, e2).stream().sorted().toList()), r.duplicateGroups());
            assertEquals(0L, r.reclaimableBytes());
        } finally {
            cleanup(root);
        }
    }

    @Test("hashing runs on multiple threads")
    public void concurrentHashing() throws Exception {
        Path root = tempTree();
        try {
            for (int i = 0; i < 12; i++) write(root, "f" + i, "same-content");
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger maxInFlight = new AtomicInteger();
            ContentHasher slow = p -> {
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                inFlight.decrementAndGet();
                return "h";
            };
            DedupReport r = new Deduplicator(slow, 4).scan(root);
            assertTrue(maxInFlight.get() > 1, "expected parallel hashing, max in flight was " + maxInFlight.get());
            assertTrue(maxInFlight.get() <= 4, "must not exceed the thread count");
            assertEquals(12, r.duplicateGroups().get(0).size());
        } finally {
            cleanup(root);
        }
    }

    @Test("validates thread count")
    public void validation() {
        assertThrows(IllegalArgumentException.class, () -> new Deduplicator(new Sha256Hasher(), 0));
    }
}
