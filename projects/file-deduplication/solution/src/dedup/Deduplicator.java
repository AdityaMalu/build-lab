package dedup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

public class Deduplicator {

    private final ContentHasher hasher;
    private final int threads;

    public Deduplicator(ContentHasher hasher, int threads) {
        if (hasher == null) throw new IllegalArgumentException("hasher required");
        if (threads < 1) throw new IllegalArgumentException("threads must be >= 1");
        this.hasher = hasher;
        this.threads = threads;
    }

    public DedupReport scan(Path root) throws IOException {
        Map<Path, String> failures = new ConcurrentHashMap<>();

        // 1. group by size
        Map<Long, List<Path>> bySize = new HashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    bySize.computeIfAbsent(Files.size(p), k -> new ArrayList<>()).add(p);
                } catch (IOException e) {
                    failures.put(p, String.valueOf(e.getMessage()));
                }
            }
        }

        // 2. hash candidates concurrently
        List<Path> candidates = new ArrayList<>();
        for (List<Path> group : bySize.values()) if (group.size() > 1) candidates.addAll(group);
        Map<Path, String> hashes = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Path p : candidates) {
                futures.add(pool.submit(() -> {
                    try {
                        String h = hasher.hash(p);
                        if (h == null) throw new IOException("hasher returned null");
                        hashes.put(p, h);
                    } catch (Exception e) {
                        failures.put(p, String.valueOf(e.getMessage()));
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                } catch (ExecutionException e) {
                    throw new IOException(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // 3. within (size, hash) buckets, confirm with exact comparison
        List<List<Path>> groups = new ArrayList<>();
        long reclaimable = 0;
        for (Map.Entry<Long, List<Path>> e : bySize.entrySet()) {
            if (e.getValue().size() < 2) continue;
            Map<String, List<Path>> byHash = new TreeMap<>();
            for (Path p : e.getValue()) {
                String h = hashes.get(p);
                if (h != null) byHash.computeIfAbsent(h, k -> new ArrayList<>()).add(p);
            }
            for (List<Path> sameHash : byHash.values()) {
                for (List<Path> confirmed : exactGroups(sameHash, failures)) {
                    if (confirmed.size() < 2) continue;
                    confirmed.sort(Comparator.naturalOrder());
                    groups.add(List.copyOf(confirmed));
                    reclaimable += e.getKey() * (confirmed.size() - 1);
                }
            }
        }
        groups.sort(Comparator.comparing(g -> g.get(0)));
        return new DedupReport(List.copyOf(groups), Map.copyOf(failures), reclaimable);
    }

    private static List<List<Path>> exactGroups(List<Path> files, Map<Path, String> failures) {
        List<List<Path>> out = new ArrayList<>();
        LinkedList<Path> remaining = new LinkedList<>(files);
        remaining.sort(Comparator.naturalOrder());
        while (!remaining.isEmpty()) {
            Path rep = remaining.removeFirst();
            List<Path> group = new ArrayList<>(List.of(rep));
            var it = remaining.iterator();
            while (it.hasNext()) {
                Path other = it.next();
                try {
                    if (Files.mismatch(rep, other) == -1L) {
                        group.add(other);
                        it.remove();
                    }
                } catch (IOException ex) {
                    // can't tell which side failed cheaply; record the other and move on
                    failures.put(other, String.valueOf(ex.getMessage()));
                    it.remove();
                }
            }
            out.add(group);
        }
        return out;
    }
}
