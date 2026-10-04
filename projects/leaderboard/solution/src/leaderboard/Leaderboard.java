package leaderboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeSet;

/**
 * Fixes vs. the production version:
 *  - Entries are immutable. Mutating score/seq of an object inside a TreeSet corrupts the set
 *    (it can't find the old position), which caused the duplicates; now the old entry is removed
 *    and a new one inserted.
 *  - Only a strictly higher score replaces the best, and it records a new "reached at" sequence.
 *  - Ties are ordered by who reached the score first (seq), then name.
 *  - Competition ranking (1,2,2,4) instead of dense ranking (1,2,2,3).
 *  - top/around/page clip their ranges; page numbers are 1-based; around includes k below.
 *  - removePlayer removes from both structures; negative scores rejected; methods synchronized.
 */
public class Leaderboard {

    private record Entry(String player, long score, long seq) {}

    private static final Comparator<Entry> ORDER = Comparator
            .comparing(Entry::score, Comparator.reverseOrder())
            .thenComparingLong(Entry::seq)
            .thenComparing(Entry::player);

    private final Map<String, Entry> byPlayer = new HashMap<>();
    private final TreeSet<Entry> ranking = new TreeSet<>(ORDER);
    private long nextSeq = 1;

    public synchronized boolean submitScore(String player, long score) {
        if (player == null || player.isBlank()) throw new IllegalArgumentException("player required");
        if (score < 0) throw new IllegalArgumentException("score must be >= 0");
        Entry old = byPlayer.get(player);
        if (old != null && score <= old.score()) return false;
        if (old != null) ranking.remove(old);
        Entry e = new Entry(player, score, nextSeq++);
        byPlayer.put(player, e);
        ranking.add(e);
        return true;
    }

    public synchronized List<RankedEntry> top(int n) {
        if (n < 0) throw new IllegalArgumentException("n must be >= 0");
        List<RankedEntry> all = rankAll();
        return List.copyOf(all.subList(0, Math.min(n, all.size())));
    }

    public synchronized int rankOf(String player) {
        List<RankedEntry> all = rankAll();
        return all.get(indexOf(all, player)).rank();
    }

    public synchronized List<RankedEntry> around(String player, int k) {
        if (k < 0) throw new IllegalArgumentException("k must be >= 0");
        List<RankedEntry> all = rankAll();
        int idx = indexOf(all, player);
        int from = Math.max(0, idx - k);
        int to = (int) Math.min(all.size(), (long) idx + k + 1);
        return List.copyOf(all.subList(from, to));
    }

    public synchronized Page page(int pageNumber, int pageSize) {
        if (pageNumber < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("bad page");
        List<RankedEntry> all = rankAll();
        long fromL = (long) (pageNumber - 1) * pageSize;
        int from = (int) Math.min(fromL, all.size());
        int to = (int) Math.min(fromL + pageSize, all.size());
        return new Page(pageNumber, pageSize, all.size(), List.copyOf(all.subList(from, to)));
    }

    public synchronized boolean removePlayer(String player) {
        Entry e = byPlayer.remove(player);
        if (e == null) return false;
        ranking.remove(e);
        return true;
    }

    public synchronized int size() {
        return byPlayer.size();
    }

    private List<RankedEntry> rankAll() {
        List<RankedEntry> out = new ArrayList<>(ranking.size());
        int position = 0;
        int rank = 0;
        long previous = -1;
        for (Entry e : ranking) {
            position++;
            if (position == 1 || e.score() != previous) rank = position; // ties share; next rank skips
            out.add(new RankedEntry(rank, e.player(), e.score()));
            previous = e.score();
        }
        return out;
    }

    private static int indexOf(List<RankedEntry> all, String player) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).player().equals(player)) return i;
        }
        throw new NoSuchElementException("unknown player " + player);
    }
}
