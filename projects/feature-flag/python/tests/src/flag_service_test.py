from flags import Flag, FlagService, Rule, User, Variant, bucket
from labtest import assert_equal, assert_raises, assert_true, run_concurrently, test


def user(uid):
    return User(uid, {})


def simple(key, enabled, rollout):
    return Flag(key, enabled, rollout, [], [])


class FlagServiceTest:

    @test("FNV-1a bucketing matches the reference vectors")
    def fnv_vectors(self):
        assert_equal(261, bucket("", 1000), 'FNV("") = 2166136261')
        assert_equal(1678518573, bucket("a", 2_147_483_647), 'FNV("a") = 3826002220')
        assert_equal(1067252073, bucket("foobar", 2_147_483_647), 'FNV("foobar") = 3214735720')
        assert_equal(20, bucket("a", 100))

    @test("bucketing is in range and deterministic")
    def bucket_range(self):
        for i in range(1000):
            b = bucket(f"user-{i}", 100)
            assert_true(0 <= b < 100, f"out of range: {b}")
            assert_equal(b, bucket(f"user-{i}", 100))

    @test("disabled flag is off, unknown flag raises")
    def disabled_and_unknown(self):
        s = FlagService()
        s.define(simple("dark", False, 100))
        assert_equal("off", s.evaluate("dark", user("u1")))
        assert_raises(KeyError, lambda: s.evaluate("nope", user("u1")))
        assert_raises(ValueError, lambda: s.evaluate("dark", None))
        assert_raises(ValueError, lambda: s.evaluate("dark", user(" ")))

    @test("rollout 0% and 100%")
    def rollout_extremes(self):
        s = FlagService()
        s.define(simple("none", True, 0))
        s.define(simple("all", True, 100))
        for i in range(500):
            assert_equal("off", s.evaluate("none", user(f"u{i}")))
            assert_equal("on", s.evaluate("all", user(f"u{i}")))

    @test("rollout uses bucket(flagKey:userId) < percent exactly")
    def rollout_exact(self):
        s = FlagService()
        s.define(simple("checkout-v2", True, 30))
        on = 0
        for i in range(10_000):
            uid = f"user-{i}"
            expected = "on" if bucket(f"checkout-v2:{uid}", 100) < 30 else "off"
            got = s.evaluate("checkout-v2", user(uid))
            assert_equal(expected, got, f"user {uid}")
            on += got == "on"
        assert_true(2700 < on < 3300, f"about 30% should be on, got {on}")

    @test("same answer across service instances (no stored state)")
    def deterministic_across_instances(self):
        f = Flag("exp", True, 50, [], [Variant("A", 50), Variant("B", 50)])
        one, two = FlagService(), FlagService()
        one.define(f)
        two.define(f)
        for i in range(1000):
            assert_equal(one.evaluate("exp", user(f"x{i}")), two.evaluate("exp", user(f"x{i}")))

    @test("variants follow the weighted split on the :variant: salt")
    def variants(self):
        s = FlagService()
        s.define(Flag("color", True, 100, [], [Variant("red", 20), Variant("green", 30), Variant("blue", 50)]))
        counts = {}
        for i in range(10_000):
            uid = f"u{i}"
            b = bucket(f"color:variant:{uid}", 100)
            expected = "red" if b < 20 else "green" if b < 50 else "blue"
            got = s.evaluate("color", user(uid))
            assert_equal(expected, got, f"user {uid}")
            counts[got] = counts.get(got, 0) + 1
        assert_true(1700 < counts["red"] < 2300, f"red ~20%: {counts}")
        assert_true(4500 < counts["blue"] < 5500, f"blue ~50%: {counts}")

    @test("a zero-weight variant is never served")
    def zero_weight(self):
        s = FlagService()
        s.define(Flag("z", True, 100, [], [Variant("never", 0), Variant("always", 100)]))
        for i in range(300):
            assert_equal("always", s.evaluate("z", user(f"u{i}")))

    @test("targeting rules win over rollout, first match wins")
    def rules(self):
        s = FlagService()
        s.define(Flag("beta", True, 0, [Rule("country", "IN", "india-beta"), Rule("plan", "pro", "pro-beta"),
                                        Rule("country", "IN", "never-reached")], []))
        assert_equal("india-beta", s.evaluate("beta", User("1", {"country": "IN", "plan": "pro"})))
        assert_equal("pro-beta", s.evaluate("beta", User("2", {"country": "US", "plan": "pro"})))
        assert_equal("off", s.evaluate("beta", User("3", {"country": "US"})))
        assert_equal("off", s.evaluate("beta", User("4", None)), "None attributes are allowed")

    @test("rules do not apply when the flag is disabled")
    def rules_when_disabled(self):
        s = FlagService()
        s.define(Flag("k", False, 100, [Rule("vip", "yes", "vip")], []))
        assert_equal("off", s.evaluate("k", User("1", {"vip": "yes"})))

    @test("define validates input and replaces by key")
    def define_validation(self):
        s = FlagService()
        assert_raises(ValueError, lambda: s.define(simple(" ", True, 10)))
        assert_raises(ValueError, lambda: s.define(simple("k", True, 101)))
        assert_raises(ValueError, lambda: s.define(simple("k", True, -1)))
        assert_raises(ValueError, lambda: s.define(Flag("k", True, 10, None, [])))
        assert_raises(ValueError, lambda: s.define(Flag("k", True, 10, [], [Variant("a", 60), Variant("b", 30)])))
        assert_raises(ValueError, lambda: s.define(Flag("k", True, 10, [], [Variant("a", 110), Variant("b", -10)])))
        s.define(simple("k", True, 100))
        assert_equal("on", s.evaluate("k", user("a")))
        s.define(simple("k", False, 100))
        assert_equal("off", s.evaluate("k", user("a")), "redefinition replaces the flag")

    @test("caller mutating its lists after define has no effect")
    def defensive_copy(self):
        s = FlagService()
        variants = [Variant("only", 100)]
        s.define(Flag("k", True, 100, [], variants))
        variants.clear()
        variants.append(Variant("hacked", 100))
        assert_equal("only", s.evaluate("k", user("a")))

    @test("concurrent define and evaluate never see a broken flag")
    def concurrency(self):
        s = FlagService()
        a = Flag("live", True, 100, [], [Variant("A", 100)])
        b = Flag("live", True, 100, [], [Variant("B", 50), Variant("C", 50)])
        s.define(a)

        def work(i):
            for k in range(2000):
                if i == 0:
                    s.define(a if k % 2 == 0 else b)
                else:
                    v = s.evaluate("live", user(f"u{k}"))
                    assert_true(v in ("A", "B", "C"), f"unexpected {v}")

        run_concurrently(8, work)
