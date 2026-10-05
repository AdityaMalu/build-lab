package shortener;

public record LinkStats(String code, long hits, long lastAccessMillis) {
}
