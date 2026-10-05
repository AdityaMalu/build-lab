package watchlists;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

import testkit.Concurrent;
import testkit.Test;

import static testkit.Assert.*;

public class WatchlistServiceTest {

    interface Body {
        void run(Path file) throws Exception;
    }

    static void withFile(Body body) throws Exception {
        Path dir = Files.createTempDirectory("watchlists");
        try {
            body.run(dir.resolve("data").resolve("watchlists.db"));
        } finally {
            try (Stream<Path> s = Files.walk(dir)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
            }
        }
    }

    @Test("create, add, read back")
    public void basics() throws Exception {
        withFile(f -> {
            WatchlistService s = new WatchlistService(f);
            String id = s.createList("ana", "  Weekend  ");
            assertEquals("W1", id);
            assertTrue(s.addMovie("ana", id, "tt001"));
            assertTrue(s.addMovie("ana", id, "tt002"));
            Watchlist w = s.getList("ana", id);
            assertEquals("Weekend", w.name());
            assertEquals(List.of("tt001", "tt002"), w.movieIds());
        });
    }

    @Test("lists are private: reads by others are rejected")
    public void privacy() throws Exception {
        withFile(f -> {
            WatchlistService s = new WatchlistService(f);
            String id = s.createList("ana", "Secret");
            assertThrows(SecurityException.class, () -> s.getList("ben", id), "read by non-owner");
            assertThrows(SecurityException.class, () -> s.addMovie("ben", id, "x"));
            assertThrows(SecurityException.class, () -> s.removeMovie("ben", id, "x"));
            assertThrows(SecurityException.class, () -> s.deleteList("ben", id));
            assertThrows(NoSuchElementException.class, () -> s.getList("ana", "W99"));
            assertTrue(s.listsOf("ben").isEmpty());
        });
    }

    @Test("duplicate adds are ignored; remove reports what happened")
    public void duplicates() throws Exception {
        withFile(f -> {
            WatchlistService s = new WatchlistService(f);
            String id = s.createList("ana", "L");
            assertTrue(s.addMovie("ana", id, "m1"));
            assertFalse(s.addMovie("ana", id, "m1"), "second add returns false");
            assertEquals(List.of("m1"), s.getList("ana", id).movieIds());
            assertFalse(s.removeMovie("ana", id, "nope"), "removing a missing movie returns false");
            assertTrue(s.removeMovie("ana", id, "m1"));
            assertTrue(s.getList("ana", id).movieIds().isEmpty());
        });
    }

    @Test("validation")
    public void validation() throws Exception {
        withFile(f -> {
            WatchlistService s = new WatchlistService(f);
            assertThrows(IllegalArgumentException.class, () -> s.createList(" ", "x"));
            assertThrows(IllegalArgumentException.class, () -> s.createList("ana", "   "));
            assertThrows(IllegalArgumentException.class, () -> s.createList("ana", "a|b"));
            assertThrows(IllegalArgumentException.class, () -> s.createList("ana", "line\nbreak"));
            assertThrows(IllegalArgumentException.class, () -> s.createList("ana", "x".repeat(61)));
            String id = s.createList("ana", "Kids");
            assertThrows(IllegalStateException.class, () -> s.createList("ana", "KIDS"), "same name ignoring case");
            assertNotNull(s.createList("ben", "Kids"), "other owners may reuse names");
            assertThrows(IllegalArgumentException.class, () -> s.addMovie("ana", id, "bad,id"));
            assertThrows(IllegalArgumentException.class, () -> s.addMovie("ana", id, ""));
        });
    }

    @Test("snapshots are unmodifiable and detached")
    public void snapshots() throws Exception {
        withFile(f -> {
            WatchlistService s = new WatchlistService(f);
            String id = s.createList("ana", "L");
            s.addMovie("ana", id, "b");
            s.addMovie("ana", id, "a");
            Watchlist w = s.getList("ana", id);
            assertThrows(UnsupportedOperationException.class, () -> w.movieIds().sort(null), "must be unmodifiable");
            s.addMovie("ana", id, "c");
            assertEquals(2, w.movieIds().size(), "old snapshot is not live");
            assertEquals(List.of("b", "a", "c"), s.getList("ana", id).movieIds());
        });
    }

    @Test("state survives a restart")
    public void persistence() throws Exception {
        withFile(f -> {
            WatchlistService s1 = new WatchlistService(f);
            String a = s1.createList("ana", "Weekend");
            s1.addMovie("ana", a, "m1");
            s1.addMovie("ana", a, "m2");
            s1.removeMovie("ana", a, "m1");
            String b = s1.createList("ben", "Docs");

            WatchlistService s2 = new WatchlistService(f);
            assertEquals(List.of("m2"), s2.getList("ana", a).movieIds());
            assertEquals("Docs", s2.getList("ben", b).name());
        });
    }

    @Test("deleted lists stay deleted after a restart")
    public void deletePersists() throws Exception {
        withFile(f -> {
            WatchlistService s1 = new WatchlistService(f);
            String id = s1.createList("ana", "Temp");
            s1.deleteList("ana", id);
            assertThrows(NoSuchElementException.class, () -> s1.getList("ana", id));
            WatchlistService s2 = new WatchlistService(f);
            assertThrows(NoSuchElementException.class, () -> s2.getList("ana", id), "zombie list after restart");
        });
    }

    @Test("id numbering continues after a restart (no overwrite)")
    public void idsContinue() throws Exception {
        withFile(f -> {
            WatchlistService s1 = new WatchlistService(f);
            String first = s1.createList("ana", "One");
            s1.createList("ana", "Two");
            s1.addMovie("ana", first, "keep-me");

            WatchlistService s2 = new WatchlistService(f);
            String third = s2.createList("ben", "Three");
            assertEquals("W3", third);
            assertEquals(List.of("keep-me"), s2.getList("ana", first).movieIds(), "W1 must not be overwritten");
            assertEquals(3, s2.listsOf("ana").size() + s2.listsOf("ben").size());
        });
    }

    @Test("listsOf sorts numerically by id")
    public void sorting() throws Exception {
        withFile(f -> {
            WatchlistService s = new WatchlistService(f);
            for (int i = 1; i <= 11; i++) s.createList(i % 2 == 0 ? "ana" : "zed", "list " + i);
            List<String> ids = new ArrayList<>();
            for (Watchlist w : s.listsOf("zed")) ids.add(w.id());
            assertEquals(List.of("W1", "W3", "W5", "W7", "W9", "W11"), ids);
        });
    }

    @Test("no temp files are left behind after saves")
    public void noTempLeftovers() throws Exception {
        withFile(f -> {
            WatchlistService s = new WatchlistService(f);
            String id = s.createList("ana", "L");
            for (int i = 0; i < 20; i++) s.addMovie("ana", id, "m" + i);
            try (Stream<Path> files = Files.list(f.getParent())) {
                List<Path> all = files.toList();
                assertEquals(List.of(f.getFileName().toString()),
                        all.stream().map(p -> p.getFileName().toString()).toList(),
                        "only the data file should remain");
            }
        });
    }

    @Test("concurrent writers do not lose movies")
    public void concurrency() throws Exception {
        withFile(f -> {
            WatchlistService s = new WatchlistService(f);
            String id = s.createList("ana", "Shared");
            Concurrent.run(8, i -> {
                for (int k = 0; k < 25; k++) s.addMovie("ana", id, "t" + i + "_" + k);
            });
            assertEquals(200, s.getList("ana", id).movieIds().size());
            assertEquals(200, new WatchlistService(f).getList("ana", id).movieIds().size(), "all persisted");
        });
    }
}
