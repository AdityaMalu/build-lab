package gate;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import testkit.Concurrent;
import testkit.Test;

import static testkit.Assert.*;

public class ContentGateTest {

    static final class ManualTime implements TimeSource {
        final AtomicLong now = new AtomicLong(10_000_000);

        public long nowMillis() {
            return now.get();
        }

        void advance(long ms) {
            now.addAndGet(ms);
        }
    }

    static ContentGate gate(ManualTime t) {
        ContentGate g = new ContentGate(t);
        g.addKeyword("spam");
        g.addKeyword("Free Money");
        return g;
    }

    @Test("clean posts are accepted")
    public void accepted() {
        ContentGate g = gate(new ManualTime());
        assertEquals(Decision.ACCEPTED, g.submit("u", "Hello there, nice weather"));
        assertEquals(Decision.ACCEPTED, g.submit("u", null), "null text is empty");
        assertEquals(0, g.activeStrikes("u"));
    }

    @Test("single keyword: case-insensitive, whole token")
    public void singleWord() {
        ContentGate g = gate(new ManualTime());
        assertEquals(Decision.REJECTED_KEYWORD, g.submit("a", "This is SPAM."));
        assertEquals(Decision.REJECTED_KEYWORD, g.submit("b", "spam"));
        assertEquals(Decision.ACCEPTED, g.submit("c", "spammer alert"), "'spammer' is a different token");
        assertEquals(Decision.ACCEPTED, g.submit("d", "antispam filter"));
    }

    @Test("phrases match consecutive tokens across punctuation and spacing")
    public void phrases() {
        ContentGate g = gate(new ManualTime());
        assertEquals(Decision.REJECTED_KEYWORD, g.submit("a", "Get FREE   money!!"));
        assertEquals(Decision.REJECTED_KEYWORD, g.submit("b", "free-money now"));
        assertEquals(Decision.ACCEPTED, g.submit("c", "freemoney"));
        assertEquals(Decision.ACCEPTED, g.submit("d", "free the money"));
        assertEquals(Decision.ACCEPTED, g.submit("e", "money free"));
    }

    @Test("keywords can be removed; blank keywords are rejected")
    public void keywordAdmin() {
        ContentGate g = gate(new ManualTime());
        g.removeKeyword("SPAM");
        assertEquals(Decision.ACCEPTED, g.submit("u", "spam"));
        g.removeKeyword("free    money");
        assertEquals(Decision.ACCEPTED, g.submit("u", "free money"), "normalized phrase removal");
        assertThrows(IllegalArgumentException.class, () -> g.addKeyword("   "));
        assertThrows(IllegalArgumentException.class, () -> g.addKeyword("!!!"));
        assertThrows(IllegalArgumentException.class, () -> g.submit(" ", "x"));
    }

    @Test("three strikes inside the window auto-block for 24h")
    public void autoBlock() {
        ManualTime t = new ManualTime();
        ContentGate g = gate(t);
        assertEquals(Decision.REJECTED_KEYWORD, g.submit("u", "spam"));
        assertEquals(1, g.activeStrikes("u"));
        t.advance(1000);
        assertEquals(Decision.REJECTED_KEYWORD, g.submit("u", "spam"));
        assertFalse(g.isBlocked("u"));
        t.advance(1000);
        assertEquals(Decision.REJECTED_KEYWORD, g.submit("u", "spam"), "third strike itself is a rejection");
        assertTrue(g.isBlocked("u"), "blocked after third strike");
        assertEquals(0, g.activeStrikes("u"), "strikes cleared when blocked");
        assertEquals(Decision.BLOCKED_USER, g.submit("u", "totally innocent"));
        t.advance(ContentGate.AUTO_BLOCK_MS - 1);
        assertEquals(Decision.BLOCKED_USER, g.submit("u", "hi"), "1ms before block expires");
        t.advance(1);
        assertEquals(Decision.ACCEPTED, g.submit("u", "hi"), "block expired");
    }

    @Test("strikes expire after the window")
    public void strikeWindow() {
        ManualTime t = new ManualTime();
        ContentGate g = gate(t);
        g.submit("u", "spam");                     // t0
        t.advance(30 * 60 * 1000L);
        g.submit("u", "spam");                     // t0 + 30m
        t.advance(30 * 60 * 1000L);                // t0 + 60m: first strike expires exactly now
        assertEquals(1, g.activeStrikes("u"));
        assertEquals(Decision.REJECTED_KEYWORD, g.submit("u", "spam"));
        assertFalse(g.isBlocked("u"), "only 2 strikes in the window");
        assertEquals(2, g.activeStrikes("u"));
    }

    @Test("blocked users get no strikes; unblock clears everything")
    public void manualBlock() {
        ManualTime t = new ManualTime();
        ContentGate g = gate(t);
        g.submit("u", "spam");
        g.blockUser("u", 0); // permanent
        assertEquals(Decision.BLOCKED_USER, g.submit("u", "spam"));
        t.advance(365L * 24 * 3600 * 1000);
        assertTrue(g.isBlocked("u"), "permanent block");
        g.unblockUser("u");
        assertFalse(g.isBlocked("u"));
        assertEquals(0, g.activeStrikes("u"), "unblock clears strikes");
        g.blockUser("v", 5000);
        t.advance(5000);
        assertFalse(g.isBlocked("v"), "timed block ends at now + duration");
    }

    @Test("users are independent")
    public void independentUsers() {
        ContentGate g = gate(new ManualTime());
        for (int i = 0; i < 3; i++) g.submit("bad", "spam");
        assertTrue(g.isBlocked("bad"));
        assertEquals(Decision.ACCEPTED, g.submit("good", "hello"));
        assertEquals(0, g.activeStrikes("good"));
    }

    @Test("20 concurrent violations: exactly 3 rejections, the rest blocked")
    public void concurrentStrikes() throws Exception {
        for (int round = 0; round < 10; round++) {
            ContentGate g = gate(new ManualTime());
            AtomicInteger rejected = new AtomicInteger();
            AtomicInteger blocked = new AtomicInteger();
            Concurrent.run(20, i -> {
                Decision d = g.submit("racer", "free money spam");
                if (d == Decision.REJECTED_KEYWORD) rejected.incrementAndGet();
                else if (d == Decision.BLOCKED_USER) blocked.incrementAndGet();
            });
            assertEquals(3, rejected.get(), "round " + round);
            assertEquals(17, blocked.get(), "round " + round);
        }
    }

    @Test("keyword updates during traffic don't break checks")
    public void concurrentKeywordUpdates() throws Exception {
        ContentGate g = gate(new ManualTime());
        Concurrent.run(8, i -> {
            for (int k = 0; k < 500; k++) {
                if (i == 0) {
                    g.addKeyword("temp" + k);
                    g.removeKeyword("temp" + (k - 1));
                } else {
                    Decision d = g.submit("user" + i + "-" + k, "a perfectly normal sentence");
                    assertEquals(Decision.ACCEPTED, d);
                }
            }
        });
    }
}
