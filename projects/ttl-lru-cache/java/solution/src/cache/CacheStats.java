package cache;

public record CacheStats(long hits, long misses, long evictions, long expirations) {
}
