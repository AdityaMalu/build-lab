#include "labtest.hpp"
#include "return_risk.hpp"

namespace {

ReturnRequest req() {
    ReturnRequest r;
    r.orderId = "o";
    r.amountCents = 1'000;
    r.daysSincePurchase = 5;
    r.returnsLast90Days = 0;
    r.category = Category::Books;
    r.opened = false;
    r.hasReceipt = true;
    return r;
}

template <class F>
int scoreWith(F change) {
    ReturnRequest r = req();
    change(r);
    return ReturnRiskEngine().assess(r).score;
}

using Reasons = std::vector<std::string>;

}  // namespace

namespace labtest {
template <>
inline std::string show<Reasons>(const Reasons& v) {
    std::string s = "[";
    for (size_t i = 0; i < v.size(); i++) s += (i ? ", " : "") + v[i];
    return s + "]";
}
}  // namespace labtest

LAB_TEST(ReturnRiskTest, cleanRequest, "a clean request scores 0 / LOW with no reasons") {
    RiskResult r = ReturnRiskEngine().assess(req());
    ASSERT_EQ(0, r.score, "score");
    ASSERT_EQ(RiskLevel::Low, r.level, "level");
    ASSERT_TRUE(r.reasons.empty(), "no reasons");
}

LAB_TEST(ReturnRiskTest, lateBoundaries, "late-return boundaries: 30 / 31 / 60 / 61 days") {
    ASSERT_EQ(0, scoreWith([](ReturnRequest& r) { r.daysSincePurchase = 30; }), "30 days is not late");
    ASSERT_EQ(25, scoreWith([](ReturnRequest& r) { r.daysSincePurchase = 31; }), "31 days");
    ASSERT_EQ(25, scoreWith([](ReturnRequest& r) { r.daysSincePurchase = 60; }), "60 days is still the lower tier");
    ASSERT_EQ(40, scoreWith([](ReturnRequest& r) { r.daysSincePurchase = 61; }), "61 days: 40, not 25+40");
    ReturnRequest r = req();
    r.daysSincePurchase = 90;
    ASSERT_EQ(Reasons{"LATE_RETURN"}, ReturnRiskEngine().assess(r).reasons, "reasons");
}

LAB_TEST(ReturnRiskTest, amountBoundaries, "amount boundaries: $499.99 / $500 / $999.99 / $1000") {
    ASSERT_EQ(0, scoreWith([](ReturnRequest& r) { r.amountCents = 49'999; }), "$499.99");
    ASSERT_EQ(20, scoreWith([](ReturnRequest& r) { r.amountCents = 50'000; }), "exactly $500 counts");
    ASSERT_EQ(20, scoreWith([](ReturnRequest& r) { r.amountCents = 99'999; }), "$999.99");
    ASSERT_EQ(35, scoreWith([](ReturnRequest& r) { r.amountCents = 100'000; }), "exactly $1000");
}

LAB_TEST(ReturnRiskTest, returnBoundaries, "frequent returner boundaries: 2 / 3 / 5 / 6") {
    ASSERT_EQ(0, scoreWith([](ReturnRequest& r) { r.returnsLast90Days = 2; }), "2");
    ASSERT_EQ(15, scoreWith([](ReturnRequest& r) { r.returnsLast90Days = 3; }), "3");
    ASSERT_EQ(15, scoreWith([](ReturnRequest& r) { r.returnsLast90Days = 5; }), "5");
    ASSERT_EQ(30, scoreWith([](ReturnRequest& r) { r.returnsLast90Days = 6; }), "6");
}

LAB_TEST(ReturnRiskTest, openedElectronics, "opened electronics needs both conditions") {
    ASSERT_EQ(10, scoreWith([](ReturnRequest& r) { r.category = Category::Electronics; r.opened = true; }),
              "opened electronics");
    ASSERT_EQ(0, scoreWith([](ReturnRequest& r) { r.category = Category::Electronics; r.opened = false; }),
              "unopened electronics");
    ASSERT_EQ(0, scoreWith([](ReturnRequest& r) { r.category = Category::Apparel; r.opened = true; }),
              "opened apparel");
}

LAB_TEST(ReturnRiskTest, levels, "level boundaries: <30 LOW, 30 MEDIUM, 55 MEDIUM, 60 HIGH") {
    ReturnRiskEngine e;
    ReturnRequest a = req();
    a.daysSincePurchase = 45;
    ASSERT_EQ(RiskLevel::Low, e.assess(a).level, "25 points");
    ReturnRequest b = req();
    b.category = Category::Electronics;
    b.opened = true;
    b.hasReceipt = false;
    ASSERT_EQ(30, e.assess(b).score, "20 + 10");
    ASSERT_EQ(RiskLevel::Medium, e.assess(b).level, "30 is MEDIUM");
    ReturnRequest c = req();
    c.amountCents = 100'000;
    c.daysSincePurchase = 31;
    ASSERT_EQ(60, e.assess(c).score, "25 + 35");
    ASSERT_EQ(RiskLevel::High, e.assess(c).level, "60 is HIGH");
    ReturnRequest d = req();
    d.daysSincePurchase = 61;
    d.returnsLast90Days = 3;
    ASSERT_EQ(RiskLevel::Medium, e.assess(d).level, "55 is MEDIUM");
}

LAB_TEST(ReturnRiskTest, capAndOrder, "score is capped at 100 and reasons are in rulebook order") {
    ReturnRequest r = req();
    r.amountCents = 250'000;
    r.daysSincePurchase = 90;
    r.returnsLast90Days = 10;
    r.category = Category::Electronics;
    r.opened = true;
    r.hasReceipt = false;
    RiskResult res = ReturnRiskEngine().assess(r);
    ASSERT_EQ(100, res.score, "40+35+30+10+20 = 135, capped");
    ASSERT_EQ(RiskLevel::High, res.level, "level");
    ASSERT_EQ((Reasons{"LATE_RETURN", "HIGH_VALUE", "FREQUENT_RETURNER", "OPENED_ELECTRONICS", "NO_RECEIPT"}),
              res.reasons, "rulebook order");
}

LAB_TEST(ReturnRiskTest, validation, "validation") {
    ReturnRiskEngine e;
    auto with = [](auto change) {
        ReturnRequest r = req();
        change(r);
        return r;
    };
    ASSERT_THROWS(std::invalid_argument, e.assess(with([](ReturnRequest& r) { r.orderId = " "; })), "blank order id");
    ASSERT_THROWS(std::invalid_argument, e.assess(with([](ReturnRequest& r) { r.amountCents = -1; })), "negative amount");
    ASSERT_THROWS(std::invalid_argument, e.assess(with([](ReturnRequest& r) { r.daysSincePurchase = -1; })),
                  "negative days");
    ASSERT_THROWS(std::invalid_argument, e.assess(with([](ReturnRequest& r) { r.returnsLast90Days = -2; })),
                  "negative return count");
    ASSERT_THROWS(std::invalid_argument, e.assess(with([](ReturnRequest& r) { r.category.reset(); })),
                  "missing category");
}

LAB_TEST(ReturnRiskTest, batch, "batch keeps order and isolates bad rows") {
    ReturnRequest a = req();
    a.orderId = "a";
    ReturnRequest b = req();
    b.orderId = "b";
    b.amountCents = -5;
    ReturnRequest d = req();
    d.orderId = "d";
    d.amountCents = 60'000;
    std::vector<std::optional<ReturnRequest>> in{a, b, std::nullopt, d};
    auto out = ReturnRiskEngine().assessBatch(in);
    ASSERT_EQ(size_t{4}, out.size(), "one entry per input");
    ASSERT_TRUE(out[0].ok(), "row a ok");
    ASSERT_EQ(std::optional<std::string>("a"), out[0].orderId, "row a id");
    ASSERT_FALSE(out[1].ok(), "negative amount row is an error entry");
    ASSERT_EQ(std::optional<std::string>("b"), out[1].orderId, "row b id");
    ASSERT_FALSE(out[2].ok(), "empty row is an error entry");
    ASSERT_FALSE(out[2].orderId.has_value(), "empty row has no order id");
    ASSERT_TRUE(out[3].ok(), "row d ok");
    ASSERT_EQ(20, out[3].result->score, "row d score");
}
