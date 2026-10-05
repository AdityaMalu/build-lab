from labtest import assert_equal, assert_false, assert_is_none, assert_raises, assert_true, test
from returns import Category, ReturnRequest, ReturnRiskEngine, RiskLevel


def req(order_id="o", amount=1_000, days=5, returns=0, category=Category.BOOKS, opened=False, receipt=True):
    return ReturnRequest(order_id, amount, days, returns, category, opened, receipt)


class ReturnRiskTest:

    def __init__(self):
        self.engine = ReturnRiskEngine()

    def score(self, **kw):
        return self.engine.assess(req(**kw)).score

    @test("a clean request scores 0 / LOW with no reasons")
    def clean_request(self):
        r = self.engine.assess(req())
        assert_equal(0, r.score)
        assert_equal(RiskLevel.LOW, r.level)
        assert_equal((), tuple(r.reasons))

    @test("late-return boundaries: 30 / 31 / 60 / 61 days")
    def late_boundaries(self):
        assert_equal(0, self.score(days=30), "30 days is not late")
        assert_equal(25, self.score(days=31), "31 days")
        assert_equal(25, self.score(days=60), "60 days is still the lower tier")
        assert_equal(40, self.score(days=61), "61 days: 40, not 25+40")
        assert_equal(("LATE_RETURN",), tuple(self.engine.assess(req(days=90)).reasons))

    @test("amount boundaries: $499.99 / $500 / $999.99 / $1000")
    def amount_boundaries(self):
        assert_equal(0, self.score(amount=49_999))
        assert_equal(20, self.score(amount=50_000), "exactly $500 counts")
        assert_equal(20, self.score(amount=99_999))
        assert_equal(35, self.score(amount=100_000), "exactly $1000")
        assert_equal(("HIGH_VALUE",), tuple(self.engine.assess(req(amount=100_000)).reasons))

    @test("frequent returner boundaries: 2 / 3 / 5 / 6")
    def return_boundaries(self):
        assert_equal(0, self.score(returns=2))
        assert_equal(15, self.score(returns=3))
        assert_equal(15, self.score(returns=5))
        assert_equal(30, self.score(returns=6))

    @test("opened electronics needs both conditions")
    def opened_electronics(self):
        assert_equal(10, self.score(category=Category.ELECTRONICS, opened=True))
        assert_equal(0, self.score(category=Category.ELECTRONICS, opened=False), "unopened electronics")
        assert_equal(0, self.score(category=Category.APPAREL, opened=True), "opened apparel")

    @test("level boundaries: <30 LOW, 30 MEDIUM, 55 MEDIUM, 60 HIGH")
    def levels(self):
        assert_equal(RiskLevel.LOW, self.engine.assess(req(days=45)).level)
        thirty = self.engine.assess(req(category=Category.ELECTRONICS, opened=True, receipt=False))
        assert_equal(30, thirty.score)
        assert_equal(RiskLevel.MEDIUM, thirty.level)
        sixty = self.engine.assess(req(amount=100_000, days=31))
        assert_equal(60, sixty.score)
        assert_equal(RiskLevel.HIGH, sixty.level, "60 is HIGH")
        assert_equal(RiskLevel.MEDIUM, self.engine.assess(req(days=61, returns=3)).level)

    @test("score is capped at 100 and reasons are in rulebook order")
    def cap_and_order(self):
        r = self.engine.assess(req(amount=250_000, days=90, returns=10, category=Category.ELECTRONICS,
                                   opened=True, receipt=False))
        assert_equal(100, r.score, "40+35+30+10+20 = 135, capped")
        assert_equal(RiskLevel.HIGH, r.level)
        assert_equal(("LATE_RETURN", "HIGH_VALUE", "FREQUENT_RETURNER", "OPENED_ELECTRONICS", "NO_RECEIPT"),
                     tuple(r.reasons))

    @test("validation")
    def validation(self):
        e = self.engine
        assert_raises(ValueError, lambda: e.assess(None))
        assert_raises(ValueError, lambda: e.assess(req(order_id=" ")))
        assert_raises(ValueError, lambda: e.assess(req(amount=-1)))
        assert_raises(ValueError, lambda: e.assess(req(days=-1)))
        assert_raises(ValueError, lambda: e.assess(req(returns=-2)), "negative return count")
        assert_raises(ValueError, lambda: e.assess(req(category=None)), "missing category")

    @test("batch keeps order and isolates bad rows")
    def batch(self):
        out = self.engine.assess_batch([req(order_id="a"), req(order_id="b", amount=-5), None,
                                        req(order_id="d", amount=60_000)])
        assert_equal(4, len(out))
        assert_true(out[0].ok)
        assert_equal("a", out[0].order_id)
        assert_false(out[1].ok, "negative amount row is an error entry")
        assert_equal("b", out[1].order_id)
        assert_false(out[2].ok, "None row is an error entry")
        assert_is_none(out[2].order_id, "None row has no order id")
        assert_true(out[3].ok)
        assert_equal(20, out[3].result.score)

    @test("returned reasons cannot be modified by the caller")
    def immutable_reasons(self):
        r = self.engine.assess(req(days=45))
        assert_true(isinstance(r.reasons, tuple), "reasons must be a tuple")
        assert_raises(AttributeError, lambda: r.reasons.append("HACK"))
