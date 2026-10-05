import threading

from gate import AUTO_BLOCK_MS, ContentGate, Decision
from labtest import assert_equal, assert_false, assert_raises, assert_true, run_concurrently, test


class ManualTime:
    def __init__(self):
        self.now = 10_000_000
        self._lock = threading.Lock()

    def now_millis(self):
        with self._lock:
            return self.now

    def advance(self, ms):
        with self._lock:
            self.now += ms


def gate(t=None):
    g = ContentGate(t or ManualTime())
    g.add_keyword("spam")
    g.add_keyword("Free Money")
    return g


class ContentGateTest:

    @test("clean posts are accepted")
    def accepted(self):
        g = gate()
        assert_equal(Decision.ACCEPTED, g.submit("u", "Hello there, nice weather"))
        assert_equal(Decision.ACCEPTED, g.submit("u", None), "None text is empty")
        assert_equal(0, g.active_strikes("u"))

    @test("single keyword: case-insensitive, whole token")
    def single_word(self):
        g = gate()
        assert_equal(Decision.REJECTED_KEYWORD, g.submit("a", "This is SPAM."))
        assert_equal(Decision.REJECTED_KEYWORD, g.submit("b", "spam"))
        assert_equal(Decision.ACCEPTED, g.submit("c", "spammer alert"), "'spammer' is a different token")
        assert_equal(Decision.ACCEPTED, g.submit("d", "antispam filter"))

    @test("phrases match consecutive tokens across punctuation and spacing")
    def phrases(self):
        g = gate()
        assert_equal(Decision.REJECTED_KEYWORD, g.submit("a", "Get FREE   money!!"))
        assert_equal(Decision.REJECTED_KEYWORD, g.submit("b", "free-money now"))
        assert_equal(Decision.ACCEPTED, g.submit("c", "freemoney"))
        assert_equal(Decision.ACCEPTED, g.submit("d", "free the money"))
        assert_equal(Decision.ACCEPTED, g.submit("e", "money free"))

    @test("keywords can be removed; blank keywords are rejected")
    def keyword_admin(self):
        g = gate()
        g.remove_keyword("SPAM")
        assert_equal(Decision.ACCEPTED, g.submit("u", "spam"))
        g.remove_keyword("free    money")
        assert_equal(Decision.ACCEPTED, g.submit("u", "free money"), "normalized phrase removal")
        assert_raises(ValueError, lambda: g.add_keyword("   "))
        assert_raises(ValueError, lambda: g.add_keyword("!!!"))
        assert_raises(ValueError, lambda: g.submit(" ", "x"))

    @test("three strikes inside the window auto-block for 24h")
    def auto_block(self):
        t = ManualTime()
        g = gate(t)
        assert_equal(Decision.REJECTED_KEYWORD, g.submit("u", "spam"))
        assert_equal(1, g.active_strikes("u"))
        t.advance(1000)
        assert_equal(Decision.REJECTED_KEYWORD, g.submit("u", "spam"))
        assert_false(g.is_blocked("u"))
        t.advance(1000)
        assert_equal(Decision.REJECTED_KEYWORD, g.submit("u", "spam"), "third strike itself is a rejection")
        assert_true(g.is_blocked("u"), "blocked after third strike")
        assert_equal(0, g.active_strikes("u"), "strikes cleared when blocked")
        assert_equal(Decision.BLOCKED_USER, g.submit("u", "totally innocent"))
        t.advance(AUTO_BLOCK_MS - 1)
        assert_equal(Decision.BLOCKED_USER, g.submit("u", "hi"), "1ms before block expires")
        t.advance(1)
        assert_equal(Decision.ACCEPTED, g.submit("u", "hi"), "block expired")

    @test("strikes expire after the window")
    def strike_window(self):
        t = ManualTime()
        g = gate(t)
        g.submit("u", "spam")
        t.advance(30 * 60 * 1000)
        g.submit("u", "spam")
        t.advance(30 * 60 * 1000)  # first strike expires exactly now
        assert_equal(1, g.active_strikes("u"))
        assert_equal(Decision.REJECTED_KEYWORD, g.submit("u", "spam"))
        assert_false(g.is_blocked("u"), "only 2 strikes in the window")
        assert_equal(2, g.active_strikes("u"))

    @test("blocked users get no strikes; unblock clears everything")
    def manual_block(self):
        t = ManualTime()
        g = gate(t)
        g.submit("u", "spam")
        g.block_user("u", 0)  # permanent
        assert_equal(Decision.BLOCKED_USER, g.submit("u", "spam"))
        t.advance(365 * 24 * 3600 * 1000)
        assert_true(g.is_blocked("u"), "permanent block")
        g.unblock_user("u")
        assert_false(g.is_blocked("u"))
        assert_equal(0, g.active_strikes("u"), "unblock clears strikes")
        g.block_user("v", 5000)
        t.advance(5000)
        assert_false(g.is_blocked("v"), "timed block ends at now + duration")

    @test("users are independent")
    def independent_users(self):
        g = gate()
        for _ in range(3):
            g.submit("bad", "spam")
        assert_true(g.is_blocked("bad"))
        assert_equal(Decision.ACCEPTED, g.submit("good", "hello"))
        assert_equal(0, g.active_strikes("good"))

    @test("20 concurrent violations: exactly 3 rejections, the rest blocked")
    def concurrent_strikes(self):
        for round_ in range(10):
            g = gate()
            seen = []
            lock = threading.Lock()

            def go(_):
                d = g.submit("racer", "free money spam")
                with lock:
                    seen.append(d)

            run_concurrently(20, go)
            assert_equal(3, seen.count(Decision.REJECTED_KEYWORD), f"round {round_}")
            assert_equal(17, seen.count(Decision.BLOCKED_USER), f"round {round_}")

    @test("keyword updates during traffic don't break checks")
    def concurrent_keyword_updates(self):
        g = gate()

        def work(i):
            for k in range(500):
                if i == 0:
                    g.add_keyword(f"temp{k}")
                    g.remove_keyword(f"temp{k - 1}")
                else:
                    assert_equal(Decision.ACCEPTED, g.submit(f"user{i}-{k}", "a perfectly normal sentence"))

        run_concurrently(8, work)
