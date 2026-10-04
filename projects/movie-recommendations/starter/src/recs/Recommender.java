package recs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Recommender {

    private final List<Movie> catalog;
    private final List<Rating> ratings;
    private final Map<String, Set<String>> watched;
    private final Map<String, List<Movie>> cache = new HashMap<>();

    public Recommender(List<Movie> catalog, List<Rating> ratings, Map<String, Set<String>> watchHistory) {
        this.catalog = catalog;
        this.ratings = ratings;
        this.watched = watchHistory;
    }

    public List<Movie> recommend(String userId, int k) {
        if (k < 0) throw new IllegalArgumentException("k must be >= 0");
        List<Movie> ranked = cache.get(userId);
        if (ranked == null) {
            ranked = rank(userId);
            cache.put(userId, ranked);
        }
        return ranked.subList(0, k);
    }

    private List<Movie> rank(String userId) {
        Map<String, Movie> byId = new HashMap<>();
        for (Movie m : catalog) byId.put(m.id(), m);

        // genre -> [sum, count]
        Map<String, double[]> genreStats = new HashMap<>();
        Map<String, double[]> movieStats = new HashMap<>();
        for (Rating r : ratings) {
            movieStats.computeIfAbsent(r.movieId(), x -> new double[2]);
            movieStats.get(r.movieId())[0] += r.stars();
            movieStats.get(r.movieId())[1]++;
            if (r.userId().equals(userId)) {
                for (String g : byId.get(r.movieId()).genres()) {
                    double[] s = genreStats.computeIfAbsent(g, x -> new double[2]);
                    s[0] += r.stars();
                    s[1]++;
                }
            }
        }
        Map<String, Double> pref = new HashMap<>();
        genreStats.forEach((g, s) -> pref.put(g, s[0] / s[1]));

        Set<String> seen = watched.getOrDefault(userId, Set.of());
        Map<String, Double> score = new HashMap<>();
        Map<String, Double> global = new HashMap<>();
        List<Movie> candidates = new ArrayList<>();
        for (Movie m : catalog) {
            if (seen.contains(m.id())) continue;
            double sum = 0;
            for (String g : m.genres()) sum += pref.getOrDefault(g, 0.0);
            score.put(m.id(), pref.isEmpty() ? 0 : sum / pref.size());
            double[] ms = movieStats.get(m.id());
            global.put(m.id(), ms == null ? 0 : ms[0] / ms[1]);
            candidates.add(m);
        }
        candidates.sort(Comparator
                .comparing((Movie m) -> -score.get(m.id()))
                .thenComparing(m -> global.get(m.id()))
                .thenComparing(Movie::title));
        return candidates;
    }
}
