# Personalized Movie Recommendation

**Scenario.** The "Because you watched..." row on the home screen is getting complaints: people are recommended
movies they've already seen, the ranking looks random, the app crashes for users with few candidates, and QA found
that one screen's sorting changes what another screen shows. The recommender in your workspace has several bugs.
Fix it so it matches the spec below.

## Model (package `recs`)
`Movie(id, title, Set<String> genres)`, `Rating(userId, movieId, int stars)` with stars 1–5.

`Recommender(List<Movie> catalog, List<Rating> ratings, Map<String, Set<String>> watchHistory)`
`List<Movie> recommend(String userId, int k)`

## Spec
1. **Candidates:** catalog movies the user has neither **watched** (in `watchHistory`) nor **rated**.
2. **Genre preference:** for each genre, the average of the stars the user gave to movies in that genre.
   A movie with several genres counts toward each of them.
3. **Movie score:** the average of `pref(g)` over **the movie's own genres**. A genre the user never rated counts as 0.
   A movie with no genres scores 0.
4. **Global average:** the mean stars of a movie across all users (0 if nobody rated it).
5. **Ranking:** score descending, then global average descending, then title ascending, then id ascending.
6. Return the top `k`. If `k` exceeds the number of candidates, return all of them. `k < 0` or a null user →
   `IllegalArgumentException`. `k == 0` → empty list. A user with no history gets everything ranked by global average.
7. **Isolation:** every call returns a fresh, **unmodifiable** list. Mutating the inputs you passed to the constructor
   afterwards must not change future recommendations.
8. Safe to call `recommend` from many threads at once.

## Worked example
User rated *Alien* (sci-fi, horror) 5 and *Notting Hill* (romance) 2.
pref(sci-fi)=5, pref(horror)=5, pref(romance)=2. A candidate tagged {sci-fi, romance} scores (5+2)/2 = 3.5.
A candidate tagged {sci-fi, comedy} scores (5+0)/2 = 2.5.
