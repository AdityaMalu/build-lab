package shortener;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Pattern;

public class UrlShortener {

    /** 62^5: every code generated from OFFSET upward has at least 6 Base62 digits. */
    private static final long OFFSET = 916_132_832L;
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9_-]{3,32}");

    private static final class Link {
        final String code;
        final String url;
        final long expiresAt; // Long.MAX_VALUE = never
        final boolean alias;
        long hits;
        long lastAccess = -1;

        Link(String code, String url, long expiresAt, boolean alias) {
            this.code = code;
            this.url = url;
            this.expiresAt = expiresAt;
            this.alias = alias;
        }
    }

    private final TimeSource time;
    private final Map<String, Link> byCode = new HashMap<>();
    private final Map<String, String> generatedByUrl = new HashMap<>();
    private long counter = 0;

    public UrlShortener(TimeSource time) {
        if (time == null) throw new IllegalArgumentException("time required");
        this.time = time;
    }

    public synchronized String shorten(String longUrl, long ttlMillis) {
        validateUrl(longUrl);
        long now = time.nowMillis();
        String existing = generatedByUrl.get(longUrl);
        if (existing != null && byCode.get(existing).expiresAt > now) {
            return existing;
        }
        String code;
        do {
            code = Base62.encode(OFFSET + counter++);
        } while (byCode.containsKey(code)); // an alias may have taken it
        byCode.put(code, new Link(code, longUrl, expiry(now, ttlMillis), false));
        generatedByUrl.put(longUrl, code);
        return code;
    }

    public synchronized String shortenWithAlias(String longUrl, String alias, long ttlMillis) {
        validateUrl(longUrl);
        if (alias == null || !ALIAS.matcher(alias).matches()) throw new IllegalArgumentException("bad alias");
        if (byCode.containsKey(alias)) throw new IllegalStateException("alias taken");
        byCode.put(alias, new Link(alias, longUrl, expiry(time.nowMillis(), ttlMillis), true));
        return alias;
    }

    public synchronized Optional<String> resolve(String code) {
        Link link = code == null ? null : byCode.get(code);
        long now = time.nowMillis();
        if (link == null || now >= link.expiresAt) return Optional.empty();
        link.hits++;
        link.lastAccess = now;
        return Optional.of(link.url);
    }

    public synchronized LinkStats stats(String code) {
        Link link = code == null ? null : byCode.get(code);
        if (link == null) throw new NoSuchElementException("unknown code");
        return new LinkStats(link.code, link.hits, link.lastAccess);
    }

    private static long expiry(long now, long ttl) {
        if (ttl <= 0) return Long.MAX_VALUE;
        long t = now + ttl;
        return t < now ? Long.MAX_VALUE : t;
    }

    private static void validateUrl(String url) {
        if (url == null || url.length() > 2048) throw new IllegalArgumentException("bad url");
        String rest;
        if (url.startsWith("https://")) rest = url.substring(8);
        else if (url.startsWith("http://")) rest = url.substring(7);
        else throw new IllegalArgumentException("url must be http(s)");
        if (rest.isEmpty()) throw new IllegalArgumentException("missing host");
        for (int i = 0; i < url.length(); i++) {
            if (Character.isWhitespace(url.charAt(i))) throw new IllegalArgumentException("whitespace in url");
        }
    }
}
