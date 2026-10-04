package recs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import testkit.Concurrent;
import testkit.Test;

import static testkit.Assert.*;

public class RecommenderTest {

    // Invented titles so the fixtures are easy to reason about.
    static final Movie STAR_HAUL = new Movie("m1", "Star Haul", Set.of("scifi", "horror"));
    static final Movie RAINY_PARIS = new Movie("m2", "Rainy Paris", Set.of("romance"));
    static final Movie ORBIT_LOVE = new Movie("m3", "Orbit Love", Set.of("scifi", "romance"));
    static final Movie LASER_LAUGHS = new Movie("m4", "Laser Laughs", Set.of("scifi", "comedy"));
    static final Movie BAKE_OFF = new Movie("m5", "Bake Off Blues", Set.of("comedy"));
    static final Movie NIGHT_SHIFT = new Movie("m6", "Night Shift", Set.of("horror"));
    static final Movie UNTAGGED = new Movie("m7", "Untagged", Set.of());

    static List<Movie> catalog() {
        return List.of(STAR_HAUL, RAINY_PARIS, ORBIT_LOVE, LASER_LAUGHS, BAKE_OFF, NIGHT_SHIFT, UNTAGGED);
    }

    static List<String> ids(List<Movie> movies) {
        List<String> out = new ArrayList<>();
        for (Movie m : movies) out.add(m.id());
        return out;
    }

    @Test("excludes movies the user watched or rated")
    public void excludesSeen() {
        Recommender r = new Recommender(catalog(),
                List.of(new Rating("ana", "m1", 5)),
                Map.of("ana", Set.of("m2", "m6")));
        List<String> got = ids(r.recommend("ana", 10));
        assertFalse(got.contains("m1"), "rated movie recommended");
        assertFalse(got.contains("m2"), "watched movie recommended");
        assertFalse(got.contains("m6"), "watched movie recommended");
        assertEquals(4, got.size());
    }

    @Test("score = mean of preferences over the movie's own genres (worked example)")
    public void scoring() {
        Recommender r = new Recommender(catalog(),
                List.of(new Rating("ana", "m1", 5), new Rating("ana", "m2", 2)),
                Map.of());
        // candidates: m3 (5+2)/2=3.5, m6 horror=5, m4 (5+0)/2=2.5, m5 0, m7 0
        assertEquals(List.of("m6", "m3", "m4", "m5", "m7"), ids(r.recommend("ana", 10)));
    }

    @Test("ties broken by global average (desc), then title")
    public void tieBreaks() {
        List<Rating> ratings = List.of(
                new Rating("ana", "m6", 4),          // ana likes horror -> m1 scores (4+0)/2 = 2
                new Rating("bo", "m5", 5),           // m5 global 5
                new Rating("cy", "m4", 1),           // m4 global 1
                new Rating("bo", "m3", 3));          // m3 global 3
        Recommender r = new Recommender(catalog(), ratings, Map.of());
        // ana: m1 score 2; m2,m3,m4,m5,m7 score 0 -> by global desc: m5(5), m3(3), m4(1), then m2/m7 (0) by title
        assertEquals(List.of("m1", "m5", "m3", "m4", "m2", "m7"), ids(r.recommend("ana", 10)));
    }

    @Test("new user: ranked purely by global average then title")
    public void coldStart() {
        List<Rating> ratings = List.of(new Rating("x", "m2", 4), new Rating("y", "m2", 2), new Rating("x", "m4", 5));
        Recommender r = new Recommender(catalog(), ratings, Map.of());
        List<String> got = ids(r.recommend("newbie", 3));
        assertEquals(List.of("m4", "m2", "m5"), got, "m4(5), m2(3), then 'Bake Off Blues' alphabetically first");
    }

    @Test("k larger than candidates returns all; k = 0 is empty; k < 0 throws")
    public void kBounds() {
        Recommender r = new Recommender(catalog(), List.of(), Map.of("u", Set.of("m1", "m2", "m3", "m4", "m5")));
        assertEquals(2, r.recommend("u", 50).size());
        assertTrue(r.recommend("u", 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> r.recommend("u", -1));
        assertThrows(IllegalArgumentException.class, () -> r.recommend(null, 3));
    }

    @Test("results are unmodifiable and independent between calls")
    public void isolation() {
        Recommender r = new Recommender(catalog(), List.of(new Rating("ana", "m1", 5)), Map.of());
        List<Movie> first = r.recommend("ana", 3);
        assertThrows(UnsupportedOperationException.class, () -> first.clear(), "result must be unmodifiable");
        List<Movie> again = r.recommend("ana", 3);
        assertEquals(ids(first), ids(again));
        List<Movie> bigger = r.recommend("ana", 6);
        assertEquals(3, first.size(), "earlier result unaffected by a later call");
        assertEquals(6, bigger.size());
    }

    @Test("mutating constructor inputs afterwards has no effect")
    public void defensiveCopies() {
        List<Rating> ratings = new ArrayList<>(List.of(new Rating("ana", "m1", 5)));
        Map<String, Set<String>> history = new HashMap<>();
        Set<String> anaSeen = new HashSet<>(Set.of("m2"));
        history.put("ana", anaSeen);
        List<Movie> movies = new ArrayList<>(catalog());
        Recommender r = new Recommender(movies, ratings, history);
        List<String> before = ids(r.recommend("ana", 10));

        ratings.add(new Rating("ana", "m5", 5));
        anaSeen.add("m3");
        movies.clear();
        assertEquals(before, ids(r.recommend("ana", 10)));
    }

    @Test("ratings for movies not in the catalog are ignored, not a crash")
    public void unknownMovieRating() {
        Recommender r = new Recommender(catalog(), List.of(new Rating("ana", "ghost", 5), new Rating("ana", "m6", 5)), Map.of());
        assertEquals("m1", r.recommend("ana", 1).get(0).id(), "horror fan gets Star Haul (score 2.5) after Night Shift is excluded");
    }

    @Test("concurrent callers get consistent answers")
    public void concurrency() throws Exception {
        Recommender r = new Recommender(catalog(),
                List.of(new Rating("ana", "m1", 5), new Rating("ana", "m2", 2), new Rating("bo", "m5", 4)), Map.of());
        List<String> expectedAna = ids(r.recommend("ana", 4));
        List<String> expectedBo = ids(r.recommend("bo", 4));
        Concurrent.run(12, i -> {
            for (int k = 0; k < 300; k++) {
                assertEquals(expectedAna, ids(r.recommend("ana", 4)));
                assertEquals(expectedBo, ids(r.recommend("bo", 4)));
            }
        });
    }
}
