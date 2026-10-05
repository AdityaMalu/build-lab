package recs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fixes: defensive copies of all inputs; candidates exclude rated AND watched movies;
 * score averages over the movie's own genres; global average breaks ties descending;
 * no shared mutable cache (fresh unmodifiable list per call); k larger than the candidate
 * count is clamped; ratings for movies missing from the catalog are ignored.
 */
public class Recommender {

    private final List<Movie> catalog;
    private final Map<String, Movie> byId = new HashMap<>();
    private final List<Rating> ratings;
    private final Map<String, Set<String>> watched = new HashMap<>();
    private final Map<String, Double> globalAvg = new HashMap<>();

    public Recommender(List<Movie> catalog, List<Rating> ratings, Map<String, Set<String>> watchHistory) {
        List<Movie> movies = new ArrayList<>();
        for (Movie m : catalog) {
            Movie copy = new Movie(m.id(), m.title(), m.genres() == null ? Set.of() : Set.copyOf(m.genres()));
            movies.add(copy);
            byId.put(copy.id(), copy);
        }
        this.catalog = List.copyOf(movies);
        this.ratings = List.copyOf(ratings);
        if (watchHistory != null) watchHistory.forEach((u, s) -> watched.put(u, Set.copyOf(s)));

        Map<String, double[]> stats = new HashMap<>();
        for (Rating r : this.ratings) {
            double[] s = stats.computeIfAbsent(r.movieId(), x -> new double[2]);
            s[0] += r.stars();
            s[1]++;
        }
        stats.forEach((id, s) -> globalAvg.put(id, s[0] / s[1]));
    }

    public List<Movie> recommend(String userId, int k) {
        if (userId == null) throw new IllegalArgumentException("userId required");
        if (k < 0) throw new IllegalArgumentException("k must be >= 0");

        Map<String, double[]> genreStats = new HashMap<>();
        Set<String> excluded = new HashSet<>(watched.getOrDefault(userId, Set.of()));
        for (Rating r : ratings) {
            if (!r.userId().equals(userId)) continue;
            excluded.add(r.movieId());
            Movie m = byId.get(r.movieId());
            if (m == null) continue;
            for (String g : m.genres()) {
                double[] s = genreStats.computeIfAbsent(g, x -> new double[2]);
                s[0] += r.stars();
                s[1]++;
            }
        }
        Map<String, Double> pref = new HashMap<>();
        genreStats.forEach((g, s) -> pref.put(g, s[0] / s[1]));

        Map<String, Double> score = new HashMap<>();
        List<Movie> candidates = new ArrayList<>();
        for (Movie m : catalog) {
            if (excluded.contains(m.id())) continue;
            double sum = 0;
            for (String g : m.genres()) sum += pref.getOrDefault(g, 0.0);
            score.put(m.id(), m.genres().isEmpty() ? 0.0 : sum / m.genres().size());
            candidates.add(m);
        }
        candidates.sort(Comparator
                .comparing((Movie m) -> score.get(m.id()), Comparator.reverseOrder())
                .thenComparing((Movie m) -> globalAvg.getOrDefault(m.id(), 0.0), Comparator.reverseOrder())
                .thenComparing(Movie::title)
                .thenComparing(Movie::id));
        return List.copyOf(candidates.subList(0, Math.min(k, candidates.size())));
    }
}
