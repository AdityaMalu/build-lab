#include <atomic>

#include "flags.hpp"
#include "labtest.hpp"

namespace {

User user(const std::string& id) { return User{id, {}}; }

Flag simple(const std::string& key, bool enabled, int rollout) { return Flag{key, enabled, rollout, {}, {}}; }

}  // namespace

LAB_TEST(FlagServiceTest, fnvVectors, "FNV-1a bucketing matches the reference vectors") {
    ASSERT_EQ(261u, bucket("", 1000), "FNV(\"\") = 2166136261");
    ASSERT_EQ(1678518573u, bucket("a", 2147483647u), "FNV(\"a\") = 3826002220");
    ASSERT_EQ(1067252073u, bucket("foobar", 2147483647u), "FNV(\"foobar\") = 3214735720");
    ASSERT_EQ(20u, bucket("a", 100), "a % 100");
}

LAB_TEST(FlagServiceTest, bucketRange, "bucketing is in range and deterministic") {
    for (int i = 0; i < 1000; i++) {
        std::string k = "user-" + std::to_string(i);
        uint32_t b = bucket(k, 100);
        ASSERT_TRUE(b < 100, "out of range");
        ASSERT_EQ(b, bucket(k, 100), "deterministic");
    }
}

LAB_TEST(FlagServiceTest, disabledAndUnknown, "disabled flag is off, unknown flag throws") {
    FlagService s;
    s.define(simple("dark", false, 100));
    ASSERT_EQ(std::string("off"), s.evaluate("dark", user("u1")), "disabled");
    ASSERT_THROWS(std::out_of_range, s.evaluate("nope", user("u1")), "unknown flag");
    ASSERT_THROWS(std::invalid_argument, s.evaluate("dark", user(" ")), "blank user");
}

LAB_TEST(FlagServiceTest, rolloutExtremes, "rollout 0% and 100%") {
    FlagService s;
    s.define(simple("none", true, 0));
    s.define(simple("all", true, 100));
    for (int i = 0; i < 500; i++) {
        User u = user("u" + std::to_string(i));
        ASSERT_EQ(std::string("off"), s.evaluate("none", u), "0%");
        ASSERT_EQ(std::string("on"), s.evaluate("all", u), "100%");
    }
}

LAB_TEST(FlagServiceTest, rolloutExact, "rollout uses bucket(flagKey:userId) < percent exactly") {
    FlagService s;
    s.define(simple("checkout-v2", true, 30));
    int on = 0;
    for (int i = 0; i < 10000; i++) {
        std::string id = "user-" + std::to_string(i);
        std::string want = bucket("checkout-v2:" + id, 100) < 30 ? "on" : "off";
        std::string got = s.evaluate("checkout-v2", user(id));
        ASSERT_EQ(want, got, "user " + id);
        if (got == "on") on++;
    }
    ASSERT_TRUE(on > 2700 && on < 3300, "about 30% should be on, got " + std::to_string(on));
}

LAB_TEST(FlagServiceTest, deterministicAcrossInstances, "same answer across service instances (no stored state)") {
    Flag f{"exp", true, 50, {}, {{"A", 50}, {"B", 50}}};
    FlagService one, two;
    one.define(f);
    two.define(f);
    for (int i = 0; i < 1000; i++) {
        User u = user("x" + std::to_string(i));
        ASSERT_EQ(one.evaluate("exp", u), two.evaluate("exp", u), "instances disagree for " + u.id);
    }
}

LAB_TEST(FlagServiceTest, variants, "variants follow the weighted split on the :variant: salt") {
    FlagService s;
    s.define(Flag{"color", true, 100, {}, {{"red", 20}, {"green", 30}, {"blue", 50}}});
    int red = 0, blue = 0;
    for (int i = 0; i < 10000; i++) {
        std::string id = "u" + std::to_string(i);
        uint32_t b = bucket("color:variant:" + id, 100);
        std::string want = b < 20 ? "red" : b < 50 ? "green" : "blue";
        std::string got = s.evaluate("color", user(id));
        ASSERT_EQ(want, got, "user " + id);
        red += got == "red";
        blue += got == "blue";
    }
    ASSERT_TRUE(red > 1700 && red < 2300, "red ~20%: " + std::to_string(red));
    ASSERT_TRUE(blue > 4500 && blue < 5500, "blue ~50%: " + std::to_string(blue));
}

LAB_TEST(FlagServiceTest, zeroWeight, "a zero-weight variant is never served") {
    FlagService s;
    s.define(Flag{"z", true, 100, {}, {{"never", 0}, {"always", 100}}});
    for (int i = 0; i < 300; i++)
        ASSERT_EQ(std::string("always"), s.evaluate("z", user("u" + std::to_string(i))), "zero weight");
}

LAB_TEST(FlagServiceTest, rules, "targeting rules win over rollout, first match wins") {
    FlagService s;
    s.define(Flag{"beta", true, 0,
                  {{"country", "IN", "india-beta"}, {"plan", "pro", "pro-beta"}, {"country", "IN", "never-reached"}},
                  {}});
    ASSERT_EQ(std::string("india-beta"), s.evaluate("beta", User{"1", {{"country", "IN"}, {"plan", "pro"}}}), "IN");
    ASSERT_EQ(std::string("pro-beta"), s.evaluate("beta", User{"2", {{"country", "US"}, {"plan", "pro"}}}), "pro");
    ASSERT_EQ(std::string("off"), s.evaluate("beta", User{"3", {{"country", "US"}}}), "no rule");
    ASSERT_EQ(std::string("off"), s.evaluate("beta", User{"4", {}}), "no attributes");
}

LAB_TEST(FlagServiceTest, rulesWhenDisabled, "rules do not apply when the flag is disabled") {
    FlagService s;
    s.define(Flag{"k", false, 100, {{"vip", "yes", "vip"}}, {}});
    ASSERT_EQ(std::string("off"), s.evaluate("k", User{"1", {{"vip", "yes"}}}), "disabled");
}

LAB_TEST(FlagServiceTest, defineValidation, "define validates input and replaces by key") {
    FlagService s;
    ASSERT_THROWS(std::invalid_argument, s.define(simple(" ", true, 10)), "blank key");
    ASSERT_THROWS(std::invalid_argument, s.define(simple("k", true, 101)), "rollout 101");
    ASSERT_THROWS(std::invalid_argument, s.define(simple("k", true, -1)), "rollout -1");
    ASSERT_THROWS(std::invalid_argument, s.define(Flag{"k", true, 10, {}, {{"a", 60}, {"b", 30}}}), "sum 90");
    ASSERT_THROWS(std::invalid_argument, s.define(Flag{"k", true, 10, {}, {{"a", 110}, {"b", -10}}}), "negative");
    s.define(simple("k", true, 100));
    ASSERT_EQ(std::string("on"), s.evaluate("k", user("a")), "on");
    s.define(simple("k", false, 100));
    ASSERT_EQ(std::string("off"), s.evaluate("k", user("a")), "redefinition replaces the flag");
}

LAB_TEST(FlagServiceTest, defensiveCopy, "caller mutating its flag after define has no effect") {
    FlagService s;
    Flag f{"k", true, 100, {}, {{"only", 100}}};
    s.define(f);
    f.variants[0].name = "hacked";
    ASSERT_EQ(std::string("only"), s.evaluate("k", user("a")), "stored copy");
}

LAB_TEST(FlagServiceTest, concurrency, "concurrent define and evaluate never see a broken flag") {
    FlagService s;
    Flag a{"live", true, 100, {}, {{"A", 100}}};
    Flag b{"live", true, 100, {}, {{"B", 50}, {"C", 50}}};
    s.define(a);
    std::atomic<int> bad{0};
    labtest::runConcurrently(8, [&](int i) {
        for (int k = 0; k < 2000; k++) {
            if (i == 0) {
                s.define(k % 2 == 0 ? a : b);
            } else {
                std::string v = s.evaluate("live", user("u" + std::to_string(k)));
                if (v != "A" && v != "B" && v != "C") bad++;
            }
        }
    });
    ASSERT_EQ(0, bad.load(), "unexpected variants seen");
}
