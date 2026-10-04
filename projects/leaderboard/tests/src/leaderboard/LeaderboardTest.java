package leaderboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import testkit.Concurrent;
import testkit.Test;

import static testkit.Assert.*;

public class LeaderboardTest {

    static List<String> names(List<RankedEntry> entries) {
        List<String> out = new ArrayList<>();
        for (RankedEntry e : entries) out.add(e.player());
        return out;
    }

    static List<Integer> ranks(List<RankedEntry> entries) {
        List<Integer> out = new ArrayList<>();
        for (RankedEntry e : entries) out.add(e.rank());
        return out;
    }

    /** p1..p7 with scores 70, 60, ... 10. */
    static Leaderboard seven() {
        Leaderboard b = new Leaderboard();
        for (int i = 1; i <= 7; i++) b.submitScore("p" + i, 80 - 10L * i);
        return b;
    }

    @Test("a lower later score never replaces the best")
    public void bestScoreKept() {
        Leaderboard b = new Leaderboard();
        assertTrue(b.submitScore("ana", 100), "first score is a new best");
        assertFalse(b.submitScore("ana", 50), "lower score is not a new best");
        assertFalse(b.submitScore("ana", 100), "equal score is not a new best");
        assertEquals(100L, (Object) b.top(1).get(0).score());
        assertTrue(b.submitScore("ana", 150));
        assertEquals(150L, (Object) b.top(1).get(0).score());
    }

    @Test("competition ranking: ties share a rank, the next rank skips (1,2,2,4)")
    public void competitionRanking() {
        Leaderboard b = new Leaderboard();
        b.submitScore("a", 100);
        b.submitScore("b", 90);
        b.submitScore("c", 90);
        b.submitScore("d", 80);
        assertEquals(List.of(1, 2, 2, 4), ranks(b.top(10)));
        assertEquals(4, b.rankOf("d"));
        assertEquals(2, b.rankOf("c"));
    }

    @Test("equal scores: whoever reached the score first is listed first")
    public void tieOrder() {
        Leaderboard b = new Leaderboard();
        b.submitScore("zed", 900); // zed got there first
        b.submitScore("amy", 900);
        assertEquals(List.of("zed", "amy"), names(b.top(2)), "earlier achiever first, not alphabetical");
    }

    @Test("improving counts as reaching the new score at that moment")
    public void improvementResetsTieTime() {
        Leaderboard b = new Leaderboard();
        b.submitScore("amy", 500);
        b.submitScore("zed", 400);
        b.submitScore("bob", 600);
        b.submitScore("zed", 600); // zed reaches 600 after bob
        assertEquals(List.of("bob", "zed", "amy"), names(b.top(3)));
        b.submitScore("amy", 600); // amy reaches 600 last
        assertEquals(List.of("bob", "zed", "amy"), names(b.top(3)));
        assertEquals(List.of(1, 1, 1), ranks(b.top(3)));
    }

    @Test("improving many times never duplicates a player")
    public void noDuplicates() {
        Leaderboard b = new Leaderboard();
        b.submitScore("rival", 75);
        for (int s = 1; s <= 50; s++) b.submitScore("ana", s * 3);
        List<RankedEntry> all = b.top(100);
        assertEquals(2, all.size(), "each player appears exactly once");
        assertEquals(List.of("ana", "rival"), names(all));
        assertEquals(2, b.size());
    }

    @Test("top(n) is clipped to the number of players")
    public void topBounds() {
        Leaderboard b = seven();
        assertEquals(7, b.top(50).size());
        assertTrue(b.top(0).isEmpty());
        assertEquals(List.of("p1", "p2", "p3"), names(b.top(3)));
        assertThrows(IllegalArgumentException.class, () -> b.top(-1));
        assertTrue(new Leaderboard().top(5).isEmpty(), "empty board");
    }

    @Test("around: k above and k below, clipped at the ends")
    public void around() {
        Leaderboard b = seven();
        assertEquals(List.of("p2", "p3", "p4", "p5", "p6"), names(b.around("p4", 2)), "middle");
        assertEquals(List.of("p1", "p2", "p3"), names(b.around("p1", 2)), "near the top");
        assertEquals(List.of("p6", "p7"), names(b.around("p7", 1)), "at the bottom");
        assertEquals(List.of("p4"), names(b.around("p4", 0)));
        assertThrows(NoSuchElementException.class, () -> b.around("ghost", 1));
        assertThrows(IllegalArgumentException.class, () -> b.around("p1", -1));
    }

    @Test("pages are 1-based and clipped")
    public void paging() {
        Leaderboard b = seven();
        Page first = b.page(1, 3);
        assertEquals(List.of("p1", "p2", "p3"), names(first.entries()), "page 1 starts at the top");
        assertEquals(7, first.totalPlayers());
        assertEquals(List.of("p7"), names(b.page(3, 3).entries()), "last partial page");
        assertTrue(b.page(4, 3).entries().isEmpty(), "past the end");
        assertEquals(List.of(4, 5, 6), ranks(b.page(2, 3).entries()));
        assertThrows(IllegalArgumentException.class, () -> b.page(0, 3));
        assertThrows(IllegalArgumentException.class, () -> b.page(1, 0));
        assertThrows(IllegalArgumentException.class, () -> b.page(1, 101));
    }

    @Test("removed players disappear everywhere and ranks close up")
    public void remove() {
        Leaderboard b = seven();
        assertTrue(b.removePlayer("p2"));
        assertFalse(b.removePlayer("p2"));
        assertEquals(6, b.size());
        assertEquals(List.of("p1", "p3", "p4"), names(b.top(3)), "p2 must not be listed");
        assertEquals(2, b.rankOf("p3"));
        assertThrows(NoSuchElementException.class, () -> b.rankOf("p2"));
    }

    @Test("validation")
    public void validation() {
        Leaderboard b = new Leaderboard();
        assertThrows(IllegalArgumentException.class, () -> b.submitScore(" ", 10));
        assertThrows(IllegalArgumentException.class, () -> b.submitScore(null, 10));
        assertThrows(IllegalArgumentException.class, () -> b.submitScore("ana", -1), "negative score");
        assertThrows(NoSuchElementException.class, () -> b.rankOf("nobody"));
        assertEquals(0, b.size());
    }

    @Test("concurrent submissions keep every player's true best")
    public void concurrency() throws Exception {
        Leaderboard b = new Leaderboard();
        Map<String, Long> expected = new ConcurrentHashMap<>();
        Concurrent.run(8, w -> {
            Random rnd = new Random(w);
            for (int i = 0; i < 2_000; i++) {
                String player = "p" + rnd.nextInt(10);
                long score = rnd.nextInt(1_000_000);
                expected.merge(player, score, Math::max);
                b.submitScore(player, score);
            }
        });
        List<RankedEntry> all = b.top(100);
        assertEquals(10, all.size(), "one entry per player");
        for (RankedEntry e : all) {
            assertEquals(expected.get(e.player()), (Object) e.score(), "best score of " + e.player());
        }
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1).score() >= all.get(i).score(), "sorted by score");
        }
    }
}
