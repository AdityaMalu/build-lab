package flags;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import testkit.Concurrent;
import testkit.Test;

import static testkit.Assert.*;

public class FlagServiceTest {

    static User user(String id) {
        return new User(id, Map.of());
    }

    static Flag simple(String key, boolean enabled, int rollout) {
        return new Flag(key, enabled, rollout, List.of(), List.of());
    }

    @Test("FNV-1a bucketing matches the reference vectors")
    public void fnvVectors() {
        assertEquals(261, Bucketing.bucket("", 1000), "FNV(\"\") = 2166136261");
        assertEquals(1678518573, Bucketing.bucket("a", Integer.MAX_VALUE), "FNV(\"a\") = 3826002220");
        assertEquals(1067252073, Bucketing.bucket("foobar", Integer.MAX_VALUE), "FNV(\"foobar\") = 3214735720");
        assertEquals(20, Bucketing.bucket("a", 100));
    }

    @Test("bucketing is in range and deterministic")
    public void bucketRange() {
        for (int i = 0; i < 1000; i++) {
            int b = Bucketing.bucket("user-" + i, 100);
            assertTrue(b >= 0 && b < 100, "out of range: " + b);
            assertEquals(b, Bucketing.bucket("user-" + i, 100));
        }
    }

    @Test("disabled flag is off, unknown flag throws")
    public void disabledAndUnknown() {
        FlagService s = new FlagService();
        s.define(simple("dark", false, 100));
        assertEquals("off", s.evaluate("dark", user("u1")));
        assertThrows(IllegalArgumentException.class, () -> s.evaluate("nope", user("u1")));
        assertThrows(IllegalArgumentException.class, () -> s.evaluate("dark", null));
        assertThrows(IllegalArgumentException.class, () -> s.evaluate("dark", user(" ")));
    }

    @Test("rollout 0% and 100%")
    public void rolloutExtremes() {
        FlagService s = new FlagService();
        s.define(simple("none", true, 0));
        s.define(simple("all", true, 100));
        for (int i = 0; i < 500; i++) {
            assertEquals("off", s.evaluate("none", user("u" + i)));
            assertEquals("on", s.evaluate("all", user("u" + i)));
        }
    }

    @Test("rollout uses bucket(flagKey:userId) < percent exactly")
    public void rolloutExact() {
        FlagService s = new FlagService();
        s.define(simple("checkout-v2", true, 30));
        int on = 0;
        for (int i = 0; i < 10_000; i++) {
            String id = "user-" + i;
            boolean expectedOn = Bucketing.bucket("checkout-v2:" + id, 100) < 30;
            String got = s.evaluate("checkout-v2", user(id));
            assertEquals(expectedOn ? "on" : "off", got, "user " + id);
            if (got.equals("on")) on++;
        }
        assertTrue(on > 2700 && on < 3300, "about 30% should be on, got " + on);
    }

    @Test("same answer across service instances (no stored state)")
    public void deterministicAcrossInstances() {
        Flag f = new Flag("exp", true, 50, List.of(), List.of(new Variant("A", 50), new Variant("B", 50)));
        FlagService one = new FlagService();
        FlagService two = new FlagService();
        one.define(f);
        two.define(f);
        for (int i = 0; i < 1000; i++) {
            assertEquals(one.evaluate("exp", user("x" + i)), two.evaluate("exp", user("x" + i)));
        }
    }

    @Test("variants follow the weighted split on the :variant: salt")
    public void variants() {
        FlagService s = new FlagService();
        s.define(new Flag("color", true, 100, List.of(),
                List.of(new Variant("red", 20), new Variant("green", 30), new Variant("blue", 50))));
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            String id = "u" + i;
            int b = Bucketing.bucket("color:variant:" + id, 100);
            String expected = b < 20 ? "red" : b < 50 ? "green" : "blue";
            String got = s.evaluate("color", user(id));
            assertEquals(expected, got, "user " + id);
            counts.merge(got, 1, Integer::sum);
        }
        assertTrue(counts.get("red") > 1700 && counts.get("red") < 2300, "red ~20%: " + counts);
        assertTrue(counts.get("blue") > 4500 && counts.get("blue") < 5500, "blue ~50%: " + counts);
    }

    @Test("a zero-weight variant is never served")
    public void zeroWeight() {
        FlagService s = new FlagService();
        s.define(new Flag("z", true, 100, List.of(), List.of(new Variant("never", 0), new Variant("always", 100))));
        for (int i = 0; i < 300; i++) assertEquals("always", s.evaluate("z", user("u" + i)));
    }

    @Test("targeting rules win over rollout, first match wins")
    public void rules() {
        FlagService s = new FlagService();
        s.define(new Flag("beta", true, 0,
                List.of(new Rule("country", "IN", "india-beta"), new Rule("plan", "pro", "pro-beta"),
                        new Rule("country", "IN", "never-reached")),
                List.of()));
        assertEquals("india-beta", s.evaluate("beta", new User("1", Map.of("country", "IN", "plan", "pro"))));
        assertEquals("pro-beta", s.evaluate("beta", new User("2", Map.of("country", "US", "plan", "pro"))));
        assertEquals("off", s.evaluate("beta", new User("3", Map.of("country", "US"))));
        assertEquals("off", s.evaluate("beta", new User("4", null)), "null attributes are allowed");
    }

    @Test("rules do not apply when the flag is disabled")
    public void rulesWhenDisabled() {
        FlagService s = new FlagService();
        s.define(new Flag("k", false, 100, List.of(new Rule("vip", "yes", "vip")), List.of()));
        assertEquals("off", s.evaluate("k", new User("1", Map.of("vip", "yes"))));
    }

    @Test("define validates input and replaces by key")
    public void defineValidation() {
        FlagService s = new FlagService();
        assertThrows(IllegalArgumentException.class, () -> s.define(simple(" ", true, 10)));
        assertThrows(IllegalArgumentException.class, () -> s.define(simple("k", true, 101)));
        assertThrows(IllegalArgumentException.class, () -> s.define(simple("k", true, -1)));
        assertThrows(IllegalArgumentException.class, () -> s.define(new Flag("k", true, 10, null, List.of())));
        assertThrows(IllegalArgumentException.class, () -> s.define(new Flag("k", true, 10, List.of(),
                List.of(new Variant("a", 60), new Variant("b", 30)))));
        assertThrows(IllegalArgumentException.class, () -> s.define(new Flag("k", true, 10, List.of(),
                List.of(new Variant("a", 110), new Variant("b", -10)))));
        s.define(simple("k", true, 100));
        assertEquals("on", s.evaluate("k", user("a")));
        s.define(simple("k", false, 100));
        assertEquals("off", s.evaluate("k", user("a")), "redefinition replaces the flag");
    }

    @Test("caller mutating its lists after define has no effect")
    public void defensiveCopy() {
        FlagService s = new FlagService();
        List<Variant> vs = new java.util.ArrayList<>(List.of(new Variant("only", 100)));
        s.define(new Flag("k", true, 100, new java.util.ArrayList<>(), vs));
        vs.clear();
        vs.add(new Variant("hacked", 100));
        assertEquals("only", s.evaluate("k", user("a")));
    }

    @Test("concurrent define and evaluate never see a broken flag")
    public void concurrency() throws Exception {
        FlagService s = new FlagService();
        Flag a = new Flag("live", true, 100, List.of(), List.of(new Variant("A", 100)));
        Flag b = new Flag("live", true, 100, List.of(), List.of(new Variant("B", 50), new Variant("C", 50)));
        s.define(a);
        Concurrent.run(8, i -> {
            for (int k = 0; k < 2000; k++) {
                if (i == 0) s.define(k % 2 == 0 ? a : b);
                else {
                    String v = s.evaluate("live", user("u" + k));
                    assertTrue(v.equals("A") || v.equals("B") || v.equals("C"), "unexpected " + v);
                }
            }
        });
    }
}
