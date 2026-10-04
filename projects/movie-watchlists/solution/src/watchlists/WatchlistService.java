package watchlists;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Fixes: ownership enforced on reads; duplicate adds return false; removeMovie reports whether it
 * removed; deleteList persists; id counter resumes after max id on load; snapshots are
 * unmodifiable copies; atomic save via temp file + ATOMIC_MOVE; owner validated for the file format.
 */
public class WatchlistService {

    private static final class Entry {
        final String id;
        final String owner;
        final String name;
        final List<String> movies = new ArrayList<>();

        Entry(String id, String owner, String name) {
            this.id = id;
            this.owner = owner;
            this.name = name;
        }

        Watchlist snapshot() {
            return new Watchlist(id, owner, name, List.copyOf(movies));
        }
    }

    private final Path dataFile;
    private final Map<String, Entry> lists = new LinkedHashMap<>();
    private long nextId = 1;

    public WatchlistService(Path dataFile) {
        this.dataFile = dataFile.toAbsolutePath();
        load();
    }

    public synchronized String createList(String owner, String name) {
        if (owner == null || owner.isBlank() || owner.contains("|") || owner.contains("\n")) {
            throw new IllegalArgumentException("bad owner");
        }
        if (name == null) throw new IllegalArgumentException("name required");
        String n = name.trim();
        if (n.isEmpty() || n.length() > 60 || n.contains("|") || n.contains("\n") || n.contains("\r")) {
            throw new IllegalArgumentException("bad name");
        }
        for (Entry e : lists.values()) {
            if (e.owner.equals(owner) && e.name.equalsIgnoreCase(n)) throw new IllegalStateException("duplicate name");
        }
        Entry e = new Entry("W" + nextId, owner, n);
        lists.put(e.id, e);
        try {
            save();
        } catch (RuntimeException ex) {
            lists.remove(e.id); // keep memory consistent with disk
            throw ex;
        }
        nextId++;
        return e.id;
    }

    public synchronized boolean addMovie(String requester, String listId, String movieId) {
        Entry e = owned(requester, listId);
        if (movieId == null || !movieId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("bad movie id");
        if (e.movies.contains(movieId)) return false;
        e.movies.add(movieId);
        try {
            save();
        } catch (RuntimeException ex) {
            e.movies.remove(movieId);
            throw ex;
        }
        return true;
    }

    public synchronized boolean removeMovie(String requester, String listId, String movieId) {
        Entry e = owned(requester, listId);
        int idx = e.movies.indexOf(movieId);
        if (idx < 0) return false;
        e.movies.remove(idx);
        try {
            save();
        } catch (RuntimeException ex) {
            e.movies.add(idx, movieId);
            throw ex;
        }
        return true;
    }

    public synchronized Watchlist getList(String requester, String listId) {
        return owned(requester, listId).snapshot();
    }

    public synchronized List<Watchlist> listsOf(String owner) {
        List<Watchlist> out = new ArrayList<>();
        for (Entry e : lists.values()) if (e.owner.equals(owner)) out.add(e.snapshot());
        out.sort(Comparator.comparingLong(w -> number(w.id())));
        return out;
    }

    public synchronized void deleteList(String requester, String listId) {
        Entry e = owned(requester, listId);
        lists.remove(listId);
        try {
            save();
        } catch (RuntimeException ex) {
            lists.put(listId, e);
            throw ex;
        }
    }

    private Entry owned(String requester, String listId) {
        Entry e = listId == null ? null : lists.get(listId);
        if (e == null) throw new NoSuchElementException("no list " + listId);
        if (!e.owner.equals(requester)) throw new SecurityException("not your list");
        return e;
    }

    private static long number(String id) {
        return Long.parseLong(id.substring(1));
    }

    private void load() {
        if (!Files.exists(dataFile)) return;
        try {
            for (String line : Files.readAllLines(dataFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] f = line.split("\\|", -1);
                if (f.length != 4) throw new IllegalStateException("corrupt data line: " + line);
                Entry e = new Entry(f[0], f[1], f[2]);
                if (!f[3].isEmpty()) e.movies.addAll(Arrays.asList(f[3].split(",")));
                lists.put(e.id, e);
                nextId = Math.max(nextId, number(e.id) + 1);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : lists.values()) {
            sb.append(e.id).append('|').append(e.owner).append('|').append(e.name).append('|')
                    .append(String.join(",", e.movies)).append('\n');
        }
        try {
            Path dir = dataFile.getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, dataFile.getFileName().toString(), ".tmp");
            try {
                Files.writeString(tmp, sb, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException notSupported) {
                    Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
