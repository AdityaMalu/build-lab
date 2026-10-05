import random
import threading
from datetime import datetime, timezone

from labtest import assert_equal, assert_false, assert_not_equal, assert_raises, assert_true, run_concurrently, test
from reset import InMemoryUserStore, PasswordResetService, hash_password

T0 = int(datetime(2026, 3, 1, 10, 0, tzinfo=timezone.utc).timestamp() * 1000)
EMAIL = "ana@example.com"


class MutableClock:
    def __init__(self, now=T0):
        self.now = now

    def now_millis(self):
        return self.now


def store():
    return InMemoryUserStore().add(EMAIL, hash_password("old-password"))


def fixed(value):
    return lambda bound: value % bound


def service(config=None, clock=None, users=None, codes=None):
    return PasswordResetService(config or {}, clock or MutableClock(), users or store(), codes or fixed(1))


class PasswordResetTest:

    @test("codes are zero-padded to the configured length")
    def padding(self):
        assert_equal("000042", service(codes=fixed(42)).request_reset(EMAIL))
        assert_equal("00000007", service({"reset.code.length": "8"}, codes=fixed(7)).request_reset(EMAIL))

    @test("random codes always have exactly `length` digits")
    def length_always(self):
        rnd = random.SystemRandom()
        s = service(codes=lambda bound: rnd.randrange(bound))
        for _ in range(300):
            code = s.request_reset(EMAIL)
            assert_true(len(code) == 6 and code.isdigit(), f"bad code {code}")

    @test("ttl comes from reset.ttl.minutes and the injected clock")
    def ttl_from_config(self):
        clock = MutableClock()
        s = service({"reset.ttl.minutes": "30"}, clock)
        s.request_reset(EMAIL)
        assert_equal(T0 + 30 * 60_000, s.expires_at(EMAIL))
        d = service({}, clock)
        d.request_reset(EMAIL)
        assert_equal(T0 + 15 * 60_000, d.expires_at(EMAIL), "default 15 minutes")

    @test("describe_expiry uses the configured UTC offset")
    def timezone_offset(self):
        s = service({"reset.utc.offset.minutes": "330"})
        s.request_reset(EMAIL)
        assert_equal("2026-03-01 15:45 UTC+05:30", s.describe_expiry(EMAIL))
        w = service({"reset.utc.offset.minutes": "-90"})
        w.request_reset(EMAIL)
        assert_equal("2026-03-01 08:45 UTC-01:30", w.describe_expiry(EMAIL))
        u = service()
        u.request_reset(EMAIL)
        assert_equal("2026-03-01 10:15 UTC", u.describe_expiry(EMAIL))

    @test("bad configuration is rejected up front")
    def bad_config(self):
        for cfg in ({"reset.code.length": "3"}, {"reset.code.length": "11"}, {"reset.ttl.minutes": "abc"},
                    {"reset.ttl.minutes": "0"}, {"reset.utc.offset.minutes": "x"}, {"reset.utc.offset.minutes": "900"}):
            assert_raises(ValueError, lambda cfg=cfg: service(cfg), f"should reject {cfg}")

    @test("successful reset stores the hash, never the raw password")
    def stores_hash(self):
        users = store()
        s = service(users=users, codes=fixed(123456))
        code = s.request_reset(EMAIL)
        assert_true(s.confirm_reset(EMAIL, code, "brand-new-pass"))
        assert_equal(hash_password("brand-new-pass"), users.password_hash(EMAIL))
        assert_not_equal("brand-new-pass", users.password_hash(EMAIL), "plain text stored!")

    @test("codes are single-use")
    def single_use(self):
        s = service(codes=fixed(555))
        code = s.request_reset(EMAIL)
        assert_true(s.confirm_reset(EMAIL, code, "first-new-pass"))
        assert_false(s.confirm_reset(EMAIL, code, "second-new-pass"), "code reused")
        assert_raises(KeyError, lambda: s.expires_at(EMAIL), "nothing pending after use")

    @test("expiry boundary: valid 1ms before, invalid exactly at expiry")
    def expiry_boundary(self):
        clock = MutableClock()
        s = service(clock=clock, codes=fixed(9))
        code = s.request_reset(EMAIL)
        clock.now += 15 * 60_000 - 1
        assert_true(s.confirm_reset(EMAIL, code, "just-in-time"), "1ms before expiry")
        code2 = s.request_reset(EMAIL)
        clock.now += 15 * 60_000
        assert_false(s.confirm_reset(EMAIL, code2, "too-late-now"), "exactly at expiry")

    @test("a new request replaces the old code")
    def replaces(self):
        counter = iter(range(100, 1000))
        s = service(codes=lambda bound: next(counter))
        first = s.request_reset(EMAIL)
        second = s.request_reset(EMAIL)
        assert_not_equal(first, second, "test setup")
        assert_false(s.confirm_reset(EMAIL, first, "password-1"), "old code is dead")
        assert_true(s.confirm_reset(EMAIL, second, "password-2"))

    @test("short password is rejected without consuming the code")
    def short_password(self):
        s = service(codes=fixed(31337))
        code = s.request_reset(EMAIL)
        assert_raises(ValueError, lambda: s.confirm_reset(EMAIL, code, "short"))
        assert_true(s.confirm_reset(EMAIL, code, "long-enough"), "code still usable")

    @test("five wrong guesses invalidate the code")
    def attempt_limit(self):
        s = service(codes=fixed(424242))
        code = s.request_reset(EMAIL)
        for _ in range(5):
            assert_false(s.confirm_reset(EMAIL, "000000", "whatever-pass"))
        assert_false(s.confirm_reset(EMAIL, code, "whatever-pass"), "locked out after 5 failures")

    @test("four wrong guesses still allow the right one")
    def under_limit(self):
        s = service(codes=fixed(424242))
        code = s.request_reset(EMAIL)
        for _ in range(4):
            s.confirm_reset(EMAIL, "111111", "whatever-pass")
        assert_true(s.confirm_reset(EMAIL, code, "whatever-pass"))

    @test("unknown users")
    def unknown(self):
        s = service()
        assert_raises(KeyError, lambda: s.request_reset("ghost@example.com"))
        assert_false(s.confirm_reset("ghost@example.com", "000001", "some-password"))

    @test("racing confirmations with the same code: only one succeeds")
    def race(self):
        for round_ in range(20):
            s = service(codes=fixed(777))
            code = s.request_reset(EMAIL)
            wins = []
            lock = threading.Lock()

            def go(i):
                if s.confirm_reset(EMAIL, code, f"racer-pass-{i}"):
                    with lock:
                        wins.append(i)

            run_concurrently(8, go)
            assert_equal(1, len(wins), f"round {round_}")
