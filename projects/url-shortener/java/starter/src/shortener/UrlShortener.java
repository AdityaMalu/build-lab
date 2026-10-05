package shortener;

import java.util.Optional;

public class UrlShortener {

    public UrlShortener(TimeSource time) {
        // TODO
    }

    public String shorten(String longUrl, long ttlMillis) {
        throw new UnsupportedOperationException("TODO");
    }

    public String shortenWithAlias(String longUrl, String alias, long ttlMillis) {
        throw new UnsupportedOperationException("TODO");
    }

    public Optional<String> resolve(String code) {
        throw new UnsupportedOperationException("TODO");
    }

    public LinkStats stats(String code) {
        throw new UnsupportedOperationException("TODO");
    }
}
