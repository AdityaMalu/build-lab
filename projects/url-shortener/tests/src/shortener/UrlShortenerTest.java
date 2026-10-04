package shortener;

import java.util.Optional;
import java.util.Set;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import testkit.Concurrent;
import testkit.Test;

import static testkit.Assert.*;

public class UrlShortenerTest {

    static final class ManualTime implements TimeSource {
        final AtomicLong now = new AtomicLong(5_000);

        public long nowMillis() {
            return now.get();
        }
    }

    @Test("base62 encodes known values")
    public void base62Encode() {
        assertEquals("0", Base62.encode(0));
        assertEquals("9", Base62.encode(9));
        assertEquals("A", Base62.encode(10));
        assertEquals("z", Base62.encode(61));
        assertEquals("10", Base62.encode(62));
        assertEquals("zz", Base62.encode(62 * 62 - 1));
        assertThrows(IllegalArgumentException.class, () -> Base62.encode(-1));
    }

    @Test("base62 round-trips and rejects bad input")
    public void base62RoundTrip() {
        long[] samples = {0, 1, 61, 62, 3843, 916_132_832L, 123_456_789_012L, Long.MAX_VALUE};
        for (long n : samples) assertEquals(n, Base62.decode(Base62.encode(n)), "round trip " + n);
        assertThrows(IllegalArgumentException.class, () -> Base62.decode(""));
        assertThrows(IllegalArgumentException.class, () -> Base62.decode(null));
        assertThrows(IllegalArgumentException.class, () -> Base62.decode("ab-c"));
    }

    @Test("shorten returns a 6-10 char base62 code that resolves")
    public void shortenResolve() {
        UrlShortener s = new UrlShortener(new ManualTime());
        String code = s.shorten("https://example.com/a?b=c", 0);
        assertTrue(code.matches("[0-9A-Za-z]{6,10}"), "bad code format: " + code);
        assertEquals(Optional.of("https://example.com/a?b=c"), s.resolve(code));
        assertEquals(Optional.empty(), s.resolve("nope123"));
    }

    @Test("different urls get different codes, same url is deduplicated")
    public void dedup() {
        UrlShortener s = new UrlShortener(new ManualTime());
        String a = s.shorten("https://a.com", 0);
        String b = s.shorten("https://b.com", 0);
        assertNotEquals(a, b, "distinct urls need distinct codes");
        assertEquals(a, s.shorten("https://a.com", 0), "same url should reuse its code");
    }

    @Test("dedup does not reuse an expired link")
    public void dedupExpired() {
        ManualTime t = new ManualTime();
        UrlShortener s = new UrlShortener(t);
        String first = s.shorten("https://promo.com", 1000);
        t.now.addAndGet(1000);
        String second = s.shorten("https://promo.com", 1000);
        assertNotEquals(first, second, "expired link must not be handed out again");
        assertEquals(Optional.of("https://promo.com"), s.resolve(second));
    }

    @Test("url validation")
    public void urlValidation() {
        UrlShortener s = new UrlShortener(new ManualTime());
        String[] bad = {null, "", "ftp://x.com", "example.com", "https://", "http://has space.com",
                "https://" + "a".repeat(2050)};
        for (String u : bad) {
            assertThrows(IllegalArgumentException.class, () -> s.shorten(u, 0), "should reject " + u);
        }
        assertNotNull(s.shorten("http://x.io", 0), "plain http is fine");
    }

    @Test("expiry boundary: valid before t+ttl, expired at t+ttl")
    public void expiry() {
        ManualTime t = new ManualTime();
        UrlShortener s = new UrlShortener(t);
        String code = s.shorten("https://sale.com", 10_000);
        t.now.addAndGet(9_999);
        assertTrue(s.resolve(code).isPresent(), "1ms before expiry");
        t.now.addAndGet(1);
        assertTrue(s.resolve(code).isEmpty(), "exactly at expiry");
        assertEquals(1L, (Object) s.stats(code).hits(), "expired resolve does not count");
    }

    @Test("aliases: format, collisions, resolution")
    public void aliases() {
        ManualTime t = new ManualTime();
        UrlShortener s = new UrlShortener(t);
        assertEquals("summer-sale_24", s.shortenWithAlias("https://shop.com/s", "summer-sale_24", 0));
        assertEquals(Optional.of("https://shop.com/s"), s.resolve("summer-sale_24"));
        assertThrows(IllegalStateException.class, () -> s.shortenWithAlias("https://other.com", "summer-sale_24", 0));
        assertThrows(IllegalArgumentException.class, () -> s.shortenWithAlias("https://x.com", "ab", 0));
        assertThrows(IllegalArgumentException.class, () -> s.shortenWithAlias("https://x.com", "has space", 0));
        assertThrows(IllegalArgumentException.class, () -> s.shortenWithAlias("https://x.com", "x".repeat(33), 0));
        String gen = s.shorten("https://gen.com", 0);
        assertThrows(IllegalStateException.class, () -> s.shortenWithAlias("https://x.com", gen, 0),
                "alias may not steal a generated code");
    }

    @Test("an alias does not satisfy dedup for shorten()")
    public void aliasNotDeduped() {
        UrlShortener s = new UrlShortener(new ManualTime());
        s.shortenWithAlias("https://same.com", "mine", 0);
        String code = s.shorten("https://same.com", 0);
        assertNotEquals("mine", code);
    }

    @Test("stats track hits and last access")
    public void stats() {
        ManualTime t = new ManualTime();
        UrlShortener s = new UrlShortener(t);
        String code = s.shorten("https://a.com", 0);
        LinkStats fresh = s.stats(code);
        assertEquals(0L, (Object) fresh.hits());
        assertEquals(-1L, (Object) fresh.lastAccessMillis());
        s.resolve(code);
        t.now.set(7_777);
        s.resolve(code);
        LinkStats after = s.stats(code);
        assertEquals(2L, (Object) after.hits());
        assertEquals(7_777L, (Object) after.lastAccessMillis());
        assertThrows(NoSuchElementException.class, () -> s.stats("missing"));
    }

    @Test("concurrent shorten gives unique codes, concurrent resolve loses no hits")
    public void concurrency() throws Exception {
        UrlShortener s = new UrlShortener(new ManualTime());
        Set<String> codes = ConcurrentHashMap.newKeySet();
        Concurrent.run(20, i -> {
            for (int k = 0; k < 100; k++) codes.add(s.shorten("https://site.com/" + i + "/" + k, 0));
        });
        assertEquals(2000, codes.size(), "every url gets its own code");
        String hot = s.shorten("https://hot.com", 0);
        Concurrent.run(20, i -> {
            for (int k = 0; k < 500; k++) s.resolve(hot);
        });
        assertEquals(10_000L, (Object) s.stats(hot).hits());
    }
}
