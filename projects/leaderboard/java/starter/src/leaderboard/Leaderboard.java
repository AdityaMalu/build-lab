package leaderboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeSet;

public class Leaderboard {

    private static final class Entry {
        final String player;
        long score;
        long seq;

        Entry(String player, long score, long seq) {
            this.player = player;
            this.score = score;
            this.seq = seq;
        }
    }

    private static final Comparator<Entry> ORDER = Comparator
            .comparingLong((Entry e) -> -e.score)
            .thenComparing(e -> e.player);

    private final Map<String, Entry> byPlayer = new HashMap<>();
    private final TreeSet<Entry> ranking = new TreeSet<>(ORDER);
    private long nextSeq = 1;

    public boolean submitScore(String player, long score) {
        if (player == null || player.isBlank()) throw new IllegalArgumentException("player required");
        Entry e = byPlayer.get(player);
        if (e == null) {
            e = new Entry(player, score, nextSeq++);
            byPlayer.put(player, e);
            ranking.add(e);
            return true;
        }
        e.score = score;
        ranking.add(e);
        return true;
    }

    public List<RankedEntry> top(int n) {
        if (n < 0) throw new IllegalArgumentException("n must be >= 0");
        return rankAll().subList(0, n);
    }

    public int rankOf(String player) {
        for (RankedEntry r : rankAll()) {
            if (r.player().equals(player)) return r.rank();
        }
        throw new NoSuchElementException("unknown player " + player);
    }

    public List<RankedEntry> around(String player, int k) {
        if (k < 0) throw new IllegalArgumentException("k must be >= 0");
        List<RankedEntry> all = rankAll();
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).player().equals(player)) idx = i;
        }
        if (idx < 0) throw new NoSuchElementException("unknown player " + player);
        return all.subList(idx - k, idx + k);
    }

    public Page page(int pageNumber, int pageSize) {
        if (pageNumber < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("bad page");
        List<RankedEntry> all = rankAll();
        int from = Math.min(pageNumber * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return new Page(pageNumber, pageSize, all.size(), all.subList(from, to));
    }

    public boolean removePlayer(String player) {
        return byPlayer.remove(player) != null;
    }

    public int size() {
        return byPlayer.size();
    }

    private List<RankedEntry> rankAll() {
        List<RankedEntry> out = new ArrayList<>();
        int rank = 0;
        long previous = Long.MIN_VALUE;
        for (Entry e : ranking) {
            if (e.score != previous) rank++;
            out.add(new RankedEntry(rank, e.player, e.score));
            previous = e.score;
        }
        return out;
    }
}
