#include <atomic>

#include "content_gate.hpp"
#include "labtest.hpp"

namespace {

struct ManualClock : Clock {
    std::atomic<int64_t> now{10'000'000};
    int64_t nowMillis() const override { return now.load(); }
    void advance(int64_t ms) { now += ms; }
};

void addDefaults(ContentGate& g) {
    g.addKeyword("spam");
    g.addKeyword("Free Money");
}

}  // namespace

LAB_TEST(ContentGateTest, accepted, "clean posts are accepted") {
    ManualClock c;
    ContentGate g(c);
    addDefaults(g);
    ASSERT_EQ(Decision::Accepted, g.submit("u", "Hello there, nice weather"), "clean");
    ASSERT_EQ(Decision::Accepted, g.submit("u", ""), "empty text");
    ASSERT_EQ(0, g.activeStrikes("u"), "no strikes");
}

LAB_TEST(ContentGateTest, singleWord, "single keyword: case-insensitive, whole token") {
    ManualClock c;
    ContentGate g(c);
    addDefaults(g);
    ASSERT_EQ(Decision::RejectedKeyword, g.submit("a", "This is SPAM."), "upper case");
    ASSERT_EQ(Decision::RejectedKeyword, g.submit("b", "spam"), "exact");
    ASSERT_EQ(Decision::Accepted, g.submit("c", "spammer alert"), "'spammer' is a different token");
    ASSERT_EQ(Decision::Accepted, g.submit("d", "antispam filter"), "antispam");
}

LAB_TEST(ContentGateTest, phrases, "phrases match consecutive tokens across punctuation and spacing") {
    ManualClock c;
    ContentGate g(c);
    addDefaults(g);
    ASSERT_EQ(Decision::RejectedKeyword, g.submit("a", "Get FREE   money!!"), "spacing");
    ASSERT_EQ(Decision::RejectedKeyword, g.submit("b", "free-money now"), "hyphen");
    ASSERT_EQ(Decision::Accepted, g.submit("c", "freemoney"), "one token");
    ASSERT_EQ(Decision::Accepted, g.submit("d", "free the money"), "not consecutive");
    ASSERT_EQ(Decision::Accepted, g.submit("e", "money free"), "wrong order");
}

LAB_TEST(ContentGateTest, keywordAdmin, "keywords can be removed; blank keywords are rejected") {
    ManualClock c;
    ContentGate g(c);
    addDefaults(g);
    g.removeKeyword("SPAM");
    ASSERT_EQ(Decision::Accepted, g.submit("u", "spam"), "removed");
    g.removeKeyword("free    money");
    ASSERT_EQ(Decision::Accepted, g.submit("u", "free money"), "normalized phrase removal");
    ASSERT_THROWS(std::invalid_argument, g.addKeyword("   "), "blank keyword");
    ASSERT_THROWS(std::invalid_argument, g.addKeyword("!!!"), "no words");
    ASSERT_THROWS(std::invalid_argument, g.submit(" ", "x"), "blank user");
}

LAB_TEST(ContentGateTest, autoBlock, "three strikes inside the window auto-block for 24h") {
    ManualClock c;
    ContentGate g(c);
    addDefaults(g);
    ASSERT_EQ(Decision::RejectedKeyword, g.submit("u", "spam"), "strike 1");
    ASSERT_EQ(1, g.activeStrikes("u"), "one strike");
    c.advance(1000);
    ASSERT_EQ(Decision::RejectedKeyword, g.submit("u", "spam"), "strike 2");
    ASSERT_FALSE(g.isBlocked("u"), "not yet blocked");
    c.advance(1000);
    ASSERT_EQ(Decision::RejectedKeyword, g.submit("u", "spam"), "third strike itself is a rejection");
    ASSERT_TRUE(g.isBlocked("u"), "blocked after third strike");
    ASSERT_EQ(0, g.activeStrikes("u"), "strikes cleared when blocked");
    ASSERT_EQ(Decision::BlockedUser, g.submit("u", "totally innocent"), "blocked");
    c.advance(kAutoBlockMillis - 1);
    ASSERT_EQ(Decision::BlockedUser, g.submit("u", "hi"), "1ms before block expires");
    c.advance(1);
    ASSERT_EQ(Decision::Accepted, g.submit("u", "hi"), "block expired");
}

LAB_TEST(ContentGateTest, strikeWindow, "strikes expire after the window") {
    ManualClock c;
    ContentGate g(c);
    addDefaults(g);
    g.submit("u", "spam");
    c.advance(30LL * 60 * 1000);
    g.submit("u", "spam");
    c.advance(30LL * 60 * 1000);  // first strike expires exactly now
    ASSERT_EQ(1, g.activeStrikes("u"), "one active strike");
    ASSERT_EQ(Decision::RejectedKeyword, g.submit("u", "spam"), "strike");
    ASSERT_FALSE(g.isBlocked("u"), "only 2 strikes in the window");
    ASSERT_EQ(2, g.activeStrikes("u"), "two active strikes");
}

LAB_TEST(ContentGateTest, manualBlock, "blocked users get no strikes; unblock clears everything") {
    ManualClock c;
    ContentGate g(c);
    addDefaults(g);
    g.submit("u", "spam");
    g.blockUser("u", 0);  // permanent
    ASSERT_EQ(Decision::BlockedUser, g.submit("u", "spam"), "blocked");
    c.advance(365LL * 24 * 3600 * 1000);
    ASSERT_TRUE(g.isBlocked("u"), "permanent block");
    g.unblockUser("u");
    ASSERT_FALSE(g.isBlocked("u"), "unblocked");
    ASSERT_EQ(0, g.activeStrikes("u"), "unblock clears strikes");
    g.blockUser("v", 5000);
    c.advance(5000);
    ASSERT_FALSE(g.isBlocked("v"), "timed block ends at now + duration");
}

LAB_TEST(ContentGateTest, independentUsers, "users are independent") {
    ManualClock c;
    ContentGate g(c);
    addDefaults(g);
    for (int i = 0; i < 3; i++) g.submit("bad", "spam");
    ASSERT_TRUE(g.isBlocked("bad"), "bad blocked");
    ASSERT_EQ(Decision::Accepted, g.submit("good", "hello"), "good accepted");
    ASSERT_EQ(0, g.activeStrikes("good"), "good has no strikes");
}

LAB_TEST(ContentGateTest, concurrentStrikes, "20 concurrent violations: exactly 3 rejections, the rest blocked") {
    for (int round = 0; round < 10; round++) {
        ManualClock c;
        ContentGate g(c);
        addDefaults(g);
        std::atomic<int> rejected{0}, blocked{0};
        labtest::runConcurrently(20, [&](int) {
            Decision d = g.submit("racer", "free money spam");
            if (d == Decision::RejectedKeyword) rejected++;
            else if (d == Decision::BlockedUser) blocked++;
        });
        ASSERT_EQ(3, rejected.load(), "round " + std::to_string(round) + " rejections");
        ASSERT_EQ(17, blocked.load(), "round " + std::to_string(round) + " blocked");
    }
}

LAB_TEST(ContentGateTest, concurrentKeywordUpdates, "keyword updates during traffic don't break checks") {
    ManualClock c;
    ContentGate g(c);
    addDefaults(g);
    std::atomic<int> wrong{0};
    labtest::runConcurrently(8, [&](int i) {
        for (int k = 0; k < 500; k++) {
            if (i == 0) {
                g.addKeyword("temp" + std::to_string(k));
                g.removeKeyword("temp" + std::to_string(k - 1));
            } else if (g.submit("user" + std::to_string(i) + "-" + std::to_string(k), "a perfectly normal sentence") !=
                       Decision::Accepted) {
                wrong++;
            }
        }
    });
    ASSERT_EQ(0, wrong.load(), "normal posts must be accepted");
}
