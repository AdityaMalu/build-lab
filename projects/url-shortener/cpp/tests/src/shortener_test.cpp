#include <atomic>
#include <limits>
#include <mutex>
#include <regex>
#include <set>

#include "labtest.hpp"
#include "shortener.hpp"

namespace {

struct ManualClock : Clock {
    std::atomic<int64_t> now{5'000};
    int64_t nowMillis() const override { return now.load(); }
};

}  // namespace

LAB_TEST(UrlShortenerTest, base62Encode, "base62 encodes known values") {
    ASSERT_EQ(std::string("0"), base62::encode(0), "0");
    ASSERT_EQ(std::string("9"), base62::encode(9), "9");
    ASSERT_EQ(std::string("A"), base62::encode(10), "10");
    ASSERT_EQ(std::string("z"), base62::encode(61), "61");
    ASSERT_EQ(std::string("10"), base62::encode(62), "62");
    ASSERT_EQ(std::string("zz"), base62::encode(62 * 62 - 1), "3843");
    ASSERT_THROWS(std::invalid_argument, base62::encode(-1), "negative");
}

LAB_TEST(UrlShortenerTest, base62RoundTrip, "base62 round-trips and rejects bad input") {
    for (long long n : {0LL, 1LL, 61LL, 62LL, 3843LL, 916'132'832LL, 123'456'789'012LL,
                        std::numeric_limits<long long>::max()}) {
        ASSERT_EQ(n, base62::decode(base62::encode(n)), "round trip " + std::to_string(n));
    }
    ASSERT_THROWS(std::invalid_argument, base62::decode(""), "empty");
    ASSERT_THROWS(std::invalid_argument, base62::decode("ab-c"), "invalid character");
}

LAB_TEST(UrlShortenerTest, shortenResolve, "shorten returns a 6-10 char base62 code that resolves") {
    ManualClock c;
    UrlShortener s(c);
    std::string code = s.shorten("https://example.com/a?b=c", 0);
    ASSERT_TRUE(std::regex_match(code, std::regex("[0-9A-Za-z]{6,10}")), "bad code format: " + code);
    ASSERT_EQ(std::optional<std::string>("https://example.com/a?b=c"), s.resolve(code), "resolves");
    ASSERT_FALSE(s.resolve("nope123").has_value(), "unknown code");
}

LAB_TEST(UrlShortenerTest, dedup, "different urls get different codes, same url is deduplicated") {
    ManualClock c;
    UrlShortener s(c);
    std::string a = s.shorten("https://a.com", 0);
    std::string b = s.shorten("https://b.com", 0);
    ASSERT_NE(a, b, "distinct urls need distinct codes");
    ASSERT_EQ(a, s.shorten("https://a.com", 0), "same url should reuse its code");
}

LAB_TEST(UrlShortenerTest, dedupExpired, "dedup does not reuse an expired link") {
    ManualClock c;
    UrlShortener s(c);
    std::string first = s.shorten("https://promo.com", 1000);
    c.now += 1000;
    std::string second = s.shorten("https://promo.com", 1000);
    ASSERT_NE(first, second, "expired link must not be handed out again");
    ASSERT_TRUE(s.resolve(second).has_value(), "new link resolves");
}

LAB_TEST(UrlShortenerTest, urlValidation, "url validation") {
    ManualClock c;
    UrlShortener s(c);
    for (std::string bad : {std::string(""), std::string("ftp://x.com"), std::string("example.com"),
                            std::string("https://"), std::string("http://has space.com"),
                            "https://" + std::string(2050, 'a')}) {
        ASSERT_THROWS(std::invalid_argument, s.shorten(bad, 0), "should reject " + bad.substr(0, 30));
    }
    ASSERT_FALSE(s.shorten("http://x.io", 0).empty(), "plain http is fine");
}

LAB_TEST(UrlShortenerTest, expiry, "expiry boundary: valid before t+ttl, expired at t+ttl") {
    ManualClock c;
    UrlShortener s(c);
    std::string code = s.shorten("https://sale.com", 10'000);
    c.now += 9'999;
    ASSERT_TRUE(s.resolve(code).has_value(), "1ms before expiry");
    c.now += 1;
    ASSERT_FALSE(s.resolve(code).has_value(), "exactly at expiry");
    ASSERT_EQ(1LL, s.stats(code).hits, "expired resolve does not count");
}

LAB_TEST(UrlShortenerTest, aliases, "aliases: format, collisions, resolution") {
    ManualClock c;
    UrlShortener s(c);
    ASSERT_EQ(std::string("summer-sale_24"), s.shortenWithAlias("https://shop.com/s", "summer-sale_24", 0), "alias");
    ASSERT_EQ(std::optional<std::string>("https://shop.com/s"), s.resolve("summer-sale_24"), "alias resolves");
    ASSERT_THROWS(AliasTaken, s.shortenWithAlias("https://other.com", "summer-sale_24", 0), "duplicate alias");
    ASSERT_THROWS(std::invalid_argument, s.shortenWithAlias("https://x.com", "ab", 0), "too short");
    ASSERT_THROWS(std::invalid_argument, s.shortenWithAlias("https://x.com", "has space", 0), "space");
    ASSERT_THROWS(std::invalid_argument, s.shortenWithAlias("https://x.com", std::string(33, 'x'), 0), "too long");
    std::string gen = s.shorten("https://gen.com", 0);
    ASSERT_THROWS(AliasTaken, s.shortenWithAlias("https://x.com", gen, 0), "alias may not steal a generated code");
}

LAB_TEST(UrlShortenerTest, aliasNotDeduped, "an alias does not satisfy dedup for shorten()") {
    ManualClock c;
    UrlShortener s(c);
    s.shortenWithAlias("https://same.com", "mine", 0);
    ASSERT_NE(std::string("mine"), s.shorten("https://same.com", 0), "shorten must not hand out the alias");
}

LAB_TEST(UrlShortenerTest, stats, "stats track hits and last access") {
    ManualClock c;
    UrlShortener s(c);
    std::string code = s.shorten("https://a.com", 0);
    LinkStats fresh = s.stats(code);
    ASSERT_EQ(0LL, fresh.hits, "fresh hits");
    ASSERT_EQ(int64_t{-1}, fresh.lastAccessMillis, "never accessed");
    s.resolve(code);
    c.now = 7'777;
    s.resolve(code);
    LinkStats after = s.stats(code);
    ASSERT_EQ(2LL, after.hits, "hits");
    ASSERT_EQ(int64_t{7'777}, after.lastAccessMillis, "last access");
    ASSERT_THROWS(std::out_of_range, s.stats("missing"), "unknown code");
}

LAB_TEST(UrlShortenerTest, concurrency, "concurrent shorten gives unique codes, concurrent resolve loses no hits") {
    ManualClock c;
    UrlShortener s(c);
    std::mutex mu;
    std::set<std::string> codes;
    labtest::runConcurrently(20, [&](int i) {
        for (int k = 0; k < 100; k++) {
            std::string code = s.shorten("https://site.com/" + std::to_string(i) + "/" + std::to_string(k), 0);
            std::lock_guard<std::mutex> lock(mu);
            codes.insert(code);
        }
    });
    ASSERT_EQ(size_t{2000}, codes.size(), "every url gets its own code");
    std::string hot = s.shorten("https://hot.com", 0);
    labtest::runConcurrently(20, [&](int) {
        for (int k = 0; k < 500; k++) s.resolve(hot);
    });
    ASSERT_EQ(10'000LL, s.stats(hot).hits, "lost hits");
}
