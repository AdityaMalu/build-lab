package watchlists;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

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
    }

    private final Path dataFile;
    private final Map<String, Entry> lists = new LinkedHashMap<>();
    private int nextId = 1;

    public WatchlistService(Path dataFile) {
        this.dataFile = dataFile;
        load();
    }

    public synchronized String createList(String owner, String name) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner required");
        if (name == null) throw new IllegalArgumentException("name required");
        String n = name.trim();
        if (n.isEmpty() || n.length() > 60 || n.contains("|") || n.contains("\n") || n.contains("\r")) {
            throw new IllegalArgumentException("bad name");
        }
        for (Entry e : lists.values()) {
            if (e.owner.equals(owner) && e.name.equalsIgnoreCase(n)) throw new IllegalStateException("duplicate name");
        }
        Entry e = new Entry("W" + nextId++, owner, n);
        lists.put(e.id, e);
        save();
        return e.id;
    }

    public synchronized boolean addMovie(String requester, String listId, String movieId) {
        Entry e = owned(requester, listId);
        if (movieId == null || !movieId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("bad movie id");
        e.movies.add(movieId);
        save();
        return true;
    }

    public synchronized boolean removeMovie(String requester, String listId, String movieId) {
        Entry e = owned(requester, listId);
        e.movies.remove(movieId);
        save();
        return true;
    }

    public synchronized Watchlist getList(String requester, String listId) {
        Entry e = lists.get(listId);
        if (e == null) throw new NoSuchElementException("no list " + listId);
        return new Watchlist(e.id, e.owner, e.name, e.movies);
    }

    public synchronized List<Watchlist> listsOf(String owner) {
        List<Watchlist> out = new ArrayList<>();
        for (Entry e : lists.values()) {
            if (e.owner.equals(owner)) out.add(new Watchlist(e.id, e.owner, e.name, List.copyOf(e.movies)));
        }
        return out;
    }

    public synchronized void deleteList(String requester, String listId) {
        owned(requester, listId);
        lists.remove(listId);
    }

    private Entry owned(String requester, String listId) {
        Entry e = lists.get(listId);
        if (e == null) throw new NoSuchElementException("no list " + listId);
        if (!e.owner.equals(requester)) throw new SecurityException("not your list");
        return e;
    }

    private void load() {
        if (!Files.exists(dataFile)) return;
        try {
            for (String line : Files.readAllLines(dataFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] f = line.split("\\|", -1);
                Entry e = new Entry(f[0], f[1], f[2]);
                if (!f[3].isEmpty()) e.movies.addAll(Arrays.asList(f[3].split(",")));
                lists.put(e.id, e);
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
            if (dataFile.toAbsolutePath().getParent() != null) {
                Files.createDirectories(dataFile.toAbsolutePath().getParent());
            }
            Files.writeString(dataFile, sb, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
