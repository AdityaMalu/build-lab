#include <atomic>
#include <chrono>
#include <random>
#include <regex>

#include "labtest.hpp"
#include "password_reset.hpp"

namespace {

using namespace std::chrono;

const int64_t T0 = duration_cast<milliseconds>((sys_days{year{2026} / March / 1} + hours{10}).time_since_epoch()).count();
const std::string EMAIL = "ana@example.com";

struct MutableClock : Clock {
    std::atomic<int64_t> now{T0};
    int64_t nowMillis() const override { return now.load(); }
};

std::function<long long(long long)> fixed(long long v) {
    return [v](long long bound) { return v % bound; };
}

struct Fixture {
    MutableClock clock;
    InMemoryUserStore users;
    Fixture() { users.add(EMAIL, hashPassword("old-password")); }
    PasswordResetService make(std::map<std::string, std::string> cfg = {}, std::function<long long(long long)> codes = fixed(1)) {
        return PasswordResetService(std::move(cfg), clock, users, std::move(codes));
    }
};

}  // namespace

LAB_TEST(PasswordResetTest, padding, "codes are zero-padded to the configured length") {
    Fixture f;
    ASSERT_EQ(std::string("000042"), f.make({}, fixed(42)).requestReset(EMAIL), "6 digits");
    ASSERT_EQ(std::string("00000007"), f.make({{"reset.code.length", "8"}}, fixed(7)).requestReset(EMAIL), "8 digits");
}

LAB_TEST(PasswordResetTest, lengthAlways, "random codes always have exactly `length` digits") {
    Fixture f;
    std::mt19937_64 rng(12345);
    auto s = f.make({}, [&](long long bound) { return static_cast<long long>(rng() % static_cast<unsigned long long>(bound)); });
    std::regex six("\\d{6}");
    for (int i = 0; i < 300; i++) {
        std::string code = s.requestReset(EMAIL);
        ASSERT_TRUE(std::regex_match(code, six), "bad code " + code);
    }
}

LAB_TEST(PasswordResetTest, ttlFromConfig, "ttl comes from reset.ttl.minutes and the injected clock") {
    Fixture f;
    auto s = f.make({{"reset.ttl.minutes", "30"}});
    s.requestReset(EMAIL);
    ASSERT_EQ(T0 + 30 * 60'000, s.expiresAt(EMAIL), "30 minutes from the injected clock");
    auto d = f.make();
    d.requestReset(EMAIL);
    ASSERT_EQ(T0 + 15 * 60'000, d.expiresAt(EMAIL), "default 15 minutes");
}

LAB_TEST(PasswordResetTest, timezoneOffset, "describeExpiry uses the configured UTC offset") {
    Fixture f;
    auto s = f.make({{"reset.utc.offset.minutes", "330"}});
    s.requestReset(EMAIL);
    ASSERT_EQ(std::string("2026-03-01 15:45 UTC+05:30"), s.describeExpiry(EMAIL), "+05:30");
    auto w = f.make({{"reset.utc.offset.minutes", "-90"}});
    w.requestReset(EMAIL);
    ASSERT_EQ(std::string("2026-03-01 08:45 UTC-01:30"), w.describeExpiry(EMAIL), "-01:30");
    auto u = f.make();
    u.requestReset(EMAIL);
    ASSERT_EQ(std::string("2026-03-01 10:15 UTC"), u.describeExpiry(EMAIL), "UTC");
}

LAB_TEST(PasswordResetTest, badConfig, "bad configuration is rejected up front") {
    Fixture f;
    ASSERT_THROWS(std::invalid_argument, f.make({{"reset.code.length", "3"}}), "length 3");
    ASSERT_THROWS(std::invalid_argument, f.make({{"reset.code.length", "11"}}), "length 11");
    ASSERT_THROWS(std::invalid_argument, f.make({{"reset.ttl.minutes", "0"}}), "ttl 0");
    ASSERT_THROWS(std::invalid_argument, f.make({{"reset.ttl.minutes", "15min"}}), "ttl not a number");
    ASSERT_THROWS(std::invalid_argument, f.make({{"reset.utc.offset.minutes", "900"}}), "offset 900");
}

LAB_TEST(PasswordResetTest, storesHash, "successful reset stores the hash, never the raw password") {
    Fixture f;
    auto s = f.make({}, fixed(123456));
    std::string code = s.requestReset(EMAIL);
    ASSERT_TRUE(s.confirmReset(EMAIL, code, "brand-new-pass"), "reset succeeds");
    ASSERT_EQ(hashPassword("brand-new-pass"), f.users.passwordHash(EMAIL), "hash stored (plain text stored?)");
}

LAB_TEST(PasswordResetTest, singleUse, "codes are single-use") {
    Fixture f;
    auto s = f.make({}, fixed(555));
    std::string code = s.requestReset(EMAIL);
    ASSERT_TRUE(s.confirmReset(EMAIL, code, "first-new-pass"), "first use");
    ASSERT_FALSE(s.confirmReset(EMAIL, code, "second-new-pass"), "code reused");
    ASSERT_THROWS(std::out_of_range, s.expiresAt(EMAIL), "nothing pending after use");
}

LAB_TEST(PasswordResetTest, expiryBoundary, "expiry boundary: valid 1ms before, invalid exactly at expiry") {
    Fixture f;
    auto s = f.make({}, fixed(9));
    std::string code = s.requestReset(EMAIL);
    f.clock.now += 15 * 60'000 - 1;
    ASSERT_TRUE(s.confirmReset(EMAIL, code, "just-in-time"), "1ms before expiry");
    std::string code2 = s.requestReset(EMAIL);
    f.clock.now += 15 * 60'000;
    ASSERT_FALSE(s.confirmReset(EMAIL, code2, "too-late-now"), "exactly at expiry");
}

LAB_TEST(PasswordResetTest, replaces, "a new request replaces the old code") {
    Fixture f;
    long long n = 100;
    auto s = f.make({}, [&](long long) { return n++; });
    std::string first = s.requestReset(EMAIL);
    std::string second = s.requestReset(EMAIL);
    ASSERT_NE(first, second, "test setup");
    ASSERT_FALSE(s.confirmReset(EMAIL, first, "password-1"), "old code is dead");
    ASSERT_TRUE(s.confirmReset(EMAIL, second, "password-2"), "new code works");
}

LAB_TEST(PasswordResetTest, shortPassword, "short password is rejected without consuming the code") {
    Fixture f;
    auto s = f.make({}, fixed(31337));
    std::string code = s.requestReset(EMAIL);
    ASSERT_THROWS(std::invalid_argument, s.confirmReset(EMAIL, code, "short"), "short password");
    ASSERT_TRUE(s.confirmReset(EMAIL, code, "long-enough"), "code still usable");
}

LAB_TEST(PasswordResetTest, attemptLimit, "five wrong guesses invalidate the code") {
    Fixture f;
    auto s = f.make({}, fixed(424242));
    std::string code = s.requestReset(EMAIL);
    for (int i = 0; i < 5; i++) s.confirmReset(EMAIL, "000000", "whatever-pass");
    ASSERT_FALSE(s.confirmReset(EMAIL, code, "whatever-pass"), "locked out after 5 failures");
}

LAB_TEST(PasswordResetTest, underLimit, "four wrong guesses still allow the right one") {
    Fixture f;
    auto s = f.make({}, fixed(424242));
    std::string code = s.requestReset(EMAIL);
    for (int i = 0; i < 4; i++) s.confirmReset(EMAIL, "111111", "whatever-pass");
    ASSERT_TRUE(s.confirmReset(EMAIL, code, "whatever-pass"), "4 wrong guesses must not lock out");
}

LAB_TEST(PasswordResetTest, unknown, "unknown users") {
    Fixture f;
    auto s = f.make();
    ASSERT_THROWS(std::out_of_range, s.requestReset("ghost@example.com"), "unknown user");
    ASSERT_FALSE(s.confirmReset("ghost@example.com", "000001", "some-password"), "unknown user can't reset");
}

LAB_TEST(PasswordResetTest, race, "racing confirmations with the same code: only one succeeds") {
    for (int round = 0; round < 20; round++) {
        Fixture f;
        auto s = f.make({}, fixed(777));
        std::string code = s.requestReset(EMAIL);
        std::atomic<int> wins{0};
        labtest::runConcurrently(8, [&](int i) {
            if (s.confirmReset(EMAIL, code, "racer-pass-" + std::to_string(i))) wins++;
        });
        ASSERT_EQ(1, wins.load(), "round " + std::to_string(round));
    }
}
