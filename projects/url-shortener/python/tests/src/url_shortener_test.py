import threading

from labtest import (assert_equal, assert_is_none, assert_is_not_none, assert_not_equal, assert_raises,
                     assert_true, run_concurrently, test)
from shortener import AliasTakenError, UrlShortener, base62


class ManualTime:
    def __init__(self, now=5_000):
        self.now = now

    def now_millis(self):
        return self.now


class UrlShortenerTest:

    @test("base62 encodes known values")
    def base62_encode(self):
        assert_equal("0", base62.encode(0))
        assert_equal("9", base62.encode(9))
        assert_equal("A", base62.encode(10))
        assert_equal("z", base62.encode(61))
        assert_equal("10", base62.encode(62))
        assert_equal("zz", base62.encode(62 * 62 - 1))
        assert_raises(ValueError, lambda: base62.encode(-1))

    @test("base62 round-trips and rejects bad input")
    def base62_round_trip(self):
        for n in (0, 1, 61, 62, 3843, 916_132_832, 123_456_789_012, 2 ** 63 - 1):
            assert_equal(n, base62.decode(base62.encode(n)), f"round trip {n}")
        assert_raises(ValueError, lambda: base62.decode(""))
        assert_raises(ValueError, lambda: base62.decode(None))
        assert_raises(ValueError, lambda: base62.decode("ab-c"))

    @test("shorten returns a 6-10 char base62 code that resolves")
    def shorten_resolve(self):
        s = UrlShortener(ManualTime())
        code = s.shorten("https://example.com/a?b=c", 0)
        assert_true(6 <= len(code) <= 10 and code.isalnum() and code.isascii(), f"bad code format: {code}")
        assert_equal("https://example.com/a?b=c", s.resolve(code))
        assert_is_none(s.resolve("nope123"), "unknown code")

    @test("different urls get different codes, same url is deduplicated")
    def dedup(self):
        s = UrlShortener(ManualTime())
        a = s.shorten("https://a.com", 0)
        b = s.shorten("https://b.com", 0)
        assert_not_equal(a, b, "distinct urls need distinct codes")
        assert_equal(a, s.shorten("https://a.com", 0), "same url should reuse its code")

    @test("dedup does not reuse an expired link")
    def dedup_expired(self):
        t = ManualTime()
        s = UrlShortener(t)
        first = s.shorten("https://promo.com", 1000)
        t.now += 1000
        second = s.shorten("https://promo.com", 1000)
        assert_not_equal(first, second, "expired link must not be handed out again")
        assert_equal("https://promo.com", s.resolve(second))

    @test("url validation")
    def url_validation(self):
        s = UrlShortener(ManualTime())
        for bad in (None, "", "ftp://x.com", "example.com", "https://", "http://has space.com",
                    "https://" + "a" * 2050):
            assert_raises(ValueError, lambda bad=bad: s.shorten(bad, 0), f"should reject {bad!r}")
        assert_is_not_none(s.shorten("http://x.io", 0), "plain http is fine")

    @test("expiry boundary: valid before t+ttl, expired at t+ttl")
    def expiry(self):
        t = ManualTime()
        s = UrlShortener(t)
        code = s.shorten("https://sale.com", 10_000)
        t.now += 9_999
        assert_is_not_none(s.resolve(code), "1ms before expiry")
        t.now += 1
        assert_is_none(s.resolve(code), "exactly at expiry")
        assert_equal(1, s.stats(code).hits, "expired resolve does not count")

    @test("aliases: format, collisions, resolution")
    def aliases(self):
        s = UrlShortener(ManualTime())
        assert_equal("summer-sale_24", s.shorten_with_alias("https://shop.com/s", "summer-sale_24", 0))
        assert_equal("https://shop.com/s", s.resolve("summer-sale_24"))
        assert_raises(AliasTakenError, lambda: s.shorten_with_alias("https://other.com", "summer-sale_24", 0))
        assert_raises(ValueError, lambda: s.shorten_with_alias("https://x.com", "ab", 0))
        assert_raises(ValueError, lambda: s.shorten_with_alias("https://x.com", "has space", 0))
        assert_raises(ValueError, lambda: s.shorten_with_alias("https://x.com", "x" * 33, 0))
        gen = s.shorten("https://gen.com", 0)
        assert_raises(AliasTakenError, lambda: s.shorten_with_alias("https://x.com", gen, 0),
                      "alias may not steal a generated code")

    @test("an alias does not satisfy dedup for shorten()")
    def alias_not_deduped(self):
        s = UrlShortener(ManualTime())
        s.shorten_with_alias("https://same.com", "mine", 0)
        assert_not_equal("mine", s.shorten("https://same.com", 0))

    @test("stats track hits and last access")
    def stats(self):
        t = ManualTime()
        s = UrlShortener(t)
        code = s.shorten("https://a.com", 0)
        fresh = s.stats(code)
        assert_equal(0, fresh.hits)
        assert_equal(-1, fresh.last_access_millis)
        s.resolve(code)
        t.now = 7_777
        s.resolve(code)
        after = s.stats(code)
        assert_equal(2, after.hits)
        assert_equal(7_777, after.last_access_millis)
        assert_raises(KeyError, lambda: s.stats("missing"))

    @test("concurrent shorten gives unique codes, concurrent resolve loses no hits")
    def concurrency(self):
        s = UrlShortener(ManualTime())
        codes = set()
        lock = threading.Lock()

        def shorten_many(i):
            for k in range(100):
                c = s.shorten(f"https://site.com/{i}/{k}", 0)
                with lock:
                    codes.add(c)

        run_concurrently(20, shorten_many)
        assert_equal(2000, len(codes), "every url gets its own code")
        hot = s.shorten("https://hot.com", 0)
        run_concurrently(20, lambda i: [s.resolve(hot) for _ in range(500)])
        assert_equal(10_000, s.stats(hot).hits)
