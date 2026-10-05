package reset;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import testkit.Concurrent;
import testkit.Test;

import static testkit.Assert.*;

public class PasswordResetServiceTest {

    static final class MutableClock extends Clock {
        volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

    static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i + 1 < kv.length; i += 2) p.setProperty(kv[i], kv[i + 1]);
        return p;
    }

    static InMemoryUserStore store() {
        return new InMemoryUserStore().add("ana@example.com", PasswordHasher.hash("old-password"));
    }

    static CodeSource fixed(int value) {
        return bound -> value % bound;
    }

    @Test("codes are zero-padded to the configured length")
    public void padding() {
        PasswordResetService s = new PasswordResetService(props(), new MutableClock(T0), store(), fixed(42));
        assertEquals("000042", s.requestReset("ana@example.com"));
        PasswordResetService eight = new PasswordResetService(props("reset.code.length", "8"),
                new MutableClock(T0), store(), fixed(7));
        assertEquals("00000007", eight.requestReset("ana@example.com"));
    }

    @Test("random codes always have exactly `length` digits")
    public void lengthAlways() {
        PasswordResetService s = new PasswordResetService(props(), new MutableClock(T0), store(), CodeSource.secure());
        for (int i = 0; i < 300; i++) {
            String code = s.requestReset("ana@example.com");
            assertTrue(code.matches("\\d{6}"), "bad code " + code);
        }
    }

    @Test("ttl comes from reset.ttl.minutes and the injected clock")
    public void ttlFromConfig() {
        MutableClock clock = new MutableClock(T0);
        PasswordResetService s = new PasswordResetService(props("reset.ttl.minutes", "30"), clock, store(), fixed(1));
        s.requestReset("ana@example.com");
        assertEquals(T0.plus(Duration.ofMinutes(30)), s.expiresAt("ana@example.com"));
        PasswordResetService d = new PasswordResetService(props(), clock, store(), fixed(1));
        d.requestReset("ana@example.com");
        assertEquals(T0.plus(Duration.ofMinutes(15)), d.expiresAt("ana@example.com"), "default 15 minutes");
    }

    @Test("describeExpiry uses the configured timezone")
    public void timezone() {
        MutableClock clock = new MutableClock(T0);
        PasswordResetService s = new PasswordResetService(props("reset.timezone", "Asia/Kolkata"), clock, store(), fixed(1));
        s.requestReset("ana@example.com");
        assertEquals("2026-03-01 15:45 IST", s.describeExpiry("ana@example.com"));
        PasswordResetService utc = new PasswordResetService(props(), clock, store(), fixed(1));
        utc.requestReset("ana@example.com");
        assertEquals("2026-03-01 10:15 UTC", utc.describeExpiry("ana@example.com"));
    }

    @Test("bad configuration is rejected up front")
    public void badConfig() {
        MutableClock c = new MutableClock(T0);
        assertThrows(IllegalArgumentException.class, () -> new PasswordResetService(props("reset.code.length", "3"), c, store(), fixed(1)));
        assertThrows(IllegalArgumentException.class, () -> new PasswordResetService(props("reset.code.length", "11"), c, store(), fixed(1)));
        assertThrows(IllegalArgumentException.class, () -> new PasswordResetService(props("reset.ttl.minutes", "abc"), c, store(), fixed(1)));
        assertThrows(IllegalArgumentException.class, () -> new PasswordResetService(props("reset.timezone", "Mars/Olympus"), c, store(), fixed(1)));
    }

    @Test("successful reset stores the hash, never the raw password")
    public void storesHash() {
        InMemoryUserStore users = store();
        PasswordResetService s = new PasswordResetService(props(), new MutableClock(T0), users, fixed(123456));
        String code = s.requestReset("ana@example.com");
        assertTrue(s.confirmReset("ana@example.com", code, "brand-new-pass"));
        assertEquals(PasswordHasher.hash("brand-new-pass"), users.passwordHash("ana@example.com"));
        assertNotEquals("brand-new-pass", users.passwordHash("ana@example.com"), "plain text stored!");
    }

    @Test("codes are single-use")
    public void singleUse() {
        PasswordResetService s = new PasswordResetService(props(), new MutableClock(T0), store(), fixed(555));
        String code = s.requestReset("ana@example.com");
        assertTrue(s.confirmReset("ana@example.com", code, "first-new-pass"));
        assertFalse(s.confirmReset("ana@example.com", code, "second-new-pass"), "code reused");
        assertThrows(NoSuchElementException.class, () -> s.expiresAt("ana@example.com"), "nothing pending after use");
    }

    @Test("expiry boundary: valid 1ms before, invalid exactly at expiry")
    public void expiryBoundary() {
        MutableClock clock = new MutableClock(T0);
        PasswordResetService s = new PasswordResetService(props(), clock, store(), fixed(9));
        String code = s.requestReset("ana@example.com");
        clock.advance(Duration.ofMinutes(15).minusMillis(1));
        assertTrue(s.confirmReset("ana@example.com", code, "just-in-time"), "1ms before expiry");

        String code2 = s.requestReset("ana@example.com");
        clock.advance(Duration.ofMinutes(15));
        assertFalse(s.confirmReset("ana@example.com", code2, "too-late-now"), "exactly at expiry");
    }

    @Test("a new request replaces the old code")
    public void replaces() {
        AtomicInteger n = new AtomicInteger(100);
        PasswordResetService s = new PasswordResetService(props(), new MutableClock(T0), store(), b -> n.getAndIncrement());
        String first = s.requestReset("ana@example.com");
        String second = s.requestReset("ana@example.com");
        assertNotEquals(first, second, "test setup");
        assertFalse(s.confirmReset("ana@example.com", first, "password-1"), "old code is dead");
        assertTrue(s.confirmReset("ana@example.com", second, "password-2"));
    }

    @Test("short password is rejected without consuming the code")
    public void shortPassword() {
        PasswordResetService s = new PasswordResetService(props(), new MutableClock(T0), store(), fixed(31337));
        String code = s.requestReset("ana@example.com");
        assertThrows(IllegalArgumentException.class, () -> s.confirmReset("ana@example.com", code, "short"));
        assertTrue(s.confirmReset("ana@example.com", code, "long-enough"), "code still usable");
    }

    @Test("five wrong guesses invalidate the code")
    public void attemptLimit() {
        PasswordResetService s = new PasswordResetService(props(), new MutableClock(T0), store(), fixed(424242));
        String code = s.requestReset("ana@example.com");
        for (int i = 0; i < 5; i++) assertFalse(s.confirmReset("ana@example.com", "000000", "whatever-pass"));
        assertFalse(s.confirmReset("ana@example.com", code, "whatever-pass"), "locked out after 5 failures");
    }

    @Test("four wrong guesses still allow the right one")
    public void underLimit() {
        PasswordResetService s = new PasswordResetService(props(), new MutableClock(T0), store(), fixed(424242));
        String code = s.requestReset("ana@example.com");
        for (int i = 0; i < 4; i++) s.confirmReset("ana@example.com", "111111", "whatever-pass");
        assertTrue(s.confirmReset("ana@example.com", code, "whatever-pass"));
    }

    @Test("unknown users")
    public void unknown() {
        PasswordResetService s = new PasswordResetService(props(), new MutableClock(T0), store(), fixed(1));
        assertThrows(NoSuchElementException.class, () -> s.requestReset("ghost@example.com"));
        assertFalse(s.confirmReset("ghost@example.com", "000001", "some-password"));
    }

    @Test("racing confirmations with the same code: only one succeeds")
    public void race() throws Exception {
        for (int round = 0; round < 20; round++) {
            PasswordResetService s = new PasswordResetService(props(), new MutableClock(T0), store(), fixed(777));
            String code = s.requestReset("ana@example.com");
            AtomicInteger wins = new AtomicInteger();
            Concurrent.run(8, i -> {
                if (s.confirmReset("ana@example.com", code, "racer-pass-" + i)) wins.incrementAndGet();
            });
            assertEquals(1, wins.get(), "round " + round);
        }
    }
}
