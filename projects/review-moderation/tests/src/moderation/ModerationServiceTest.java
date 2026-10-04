package moderation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import testkit.Concurrent;
import testkit.Test;

import static testkit.Assert.*;

public class ModerationServiceTest {

    static ModerationService svc() {
        return new ModerationService(Set.of("spam", "ass", "Scam"));
    }

    @Test("clean comment is pending with trimmed text")
    public void pending() {
        ModerationService s = svc();
        Submission sub = s.submit("ana", "   lovely post   ");
        assertEquals("S1", sub.id());
        assertEquals(Status.PENDING, sub.status());
        assertEquals("lovely post", sub.text());
    }

    @Test("banned words are matched case-insensitively")
    public void caseInsensitive() {
        ModerationService s = svc();
        assertEquals(Status.REJECTED, s.submit("u", "SPAM link here").status());
        assertEquals(Status.REJECTED, s.submit("u", "total scam!").status(), "banned list entry 'Scam' is case-insensitive too");
        Submission r = s.submit("u", "you ass.");
        assertEquals("banned word", r.reason());
        assertEquals("auto", r.moderator());
    }

    @Test("banned words are matched as whole words only")
    public void wholeWords() {
        ModerationService s = svc();
        assertEquals(Status.PENDING, s.submit("u", "a great classic").status(), "'class' contains 'ass'");
        assertEquals(Status.PENDING, s.submit("u", "let me assess this").status());
        assertEquals(Status.PENDING, s.submit("u", "spammy-ish but fine").status(), "'spammy' is not 'spam'");
        assertEquals(Status.REJECTED, s.submit("u", "(spam)").status());
        assertEquals(Status.REJECTED, s.submit("u", "spam").status());
    }

    @Test("empty, whitespace-only and too-long text are auto-rejected")
    public void length() {
        ModerationService s = svc();
        assertEquals("invalid length", s.submit("u", "     ").reason(), "whitespace-only");
        assertEquals("invalid length", s.submit("u", "").reason());
        assertEquals("invalid length", s.submit("u", "x".repeat(1001)).reason());
        assertEquals(Status.PENDING, s.submit("u", " " + "x".repeat(1000) + " ").status(), "1000 after trim is ok");
        assertThrows(IllegalArgumentException.class, () -> s.submit(" ", "hi"));
    }

    @Test("approve and reject only from PENDING")
    public void transitions() {
        ModerationService s = svc();
        Submission a = s.submit("u", "fine");
        Submission b = s.submit("u", "also fine");
        Submission auto = s.submit("u", "spam");
        assertEquals(Status.APPROVED, s.approve(a.id(), "mod1").status());
        assertThrows(IllegalStateException.class, () -> s.reject(a.id(), "mod2", "changed mind"));
        assertEquals("mod2", s.reject(b.id(), "mod2", "off topic").moderator());
        assertThrows(IllegalStateException.class, () -> s.approve(b.id(), "mod1"), "rejected stays rejected");
        assertThrows(IllegalStateException.class, () -> s.approve(auto.id(), "mod1"), "auto-rejected stays rejected");
        assertThrows(NoSuchElementException.class, () -> s.approve("S999", "mod1"));
    }

    @Test("byStatus orders numerically by submission")
    public void ordering() {
        ModerationService s = svc();
        for (int i = 0; i < 12; i++) s.submit("u", "comment " + i);
        List<Submission> pending = s.byStatus(Status.PENDING);
        assertEquals(12, pending.size());
        List<String> ids = new ArrayList<>();
        for (Submission p : pending) ids.add(p.id());
        assertEquals(List.of("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8", "S9", "S10", "S11", "S12"), ids);
    }

    @Test("a failing listener does not undo the decision or block other listeners")
    public void listenerIsolation() {
        ModerationService s = svc();
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        s.addListener(d -> {
            throw new RuntimeException("webhook down");
        });
        s.addListener(d -> seen.add(d.id() + ":" + d.status()));
        Submission sub = s.submit("u", "hello");
        Submission decided = s.approve(sub.id(), "mod");
        assertEquals(Status.APPROVED, decided.status());
        assertEquals(1, s.byStatus(Status.APPROVED).size(), "approval must be saved");
        assertEquals(List.of(sub.id() + ":APPROVED"), List.copyOf(seen), "second listener still called");
        assertEquals(1, s.failedNotifications());
    }

    @Test("listeners are not called for auto-rejections")
    public void noListenerForAuto() {
        ModerationService s = svc();
        AtomicInteger calls = new AtomicInteger();
        s.addListener(d -> calls.incrementAndGet());
        s.submit("u", "spam");
        s.submit("u", "");
        assertEquals(0, calls.get());
    }

    @Test("racing moderators: exactly one decision wins")
    public void race() throws Exception {
        for (int round = 0; round < 20; round++) {
            ModerationService s = svc();
            Submission sub = s.submit("u", "contested");
            AtomicInteger wins = new AtomicInteger();
            Concurrent.run(8, i -> {
                try {
                    if (i % 2 == 0) s.approve(sub.id(), "m" + i);
                    else s.reject(sub.id(), "m" + i, "no");
                    wins.incrementAndGet();
                } catch (IllegalStateException lost) {
                    // expected for all but one
                }
            });
            assertEquals(1, wins.get(), "round " + round);
        }
    }

    @Test("ids are unique under concurrent submissions")
    public void uniqueIds() throws Exception {
        ModerationService s = svc();
        Set<String> ids = ConcurrentHashMap.newKeySet();
        Concurrent.run(16, i -> {
            for (int k = 0; k < 200; k++) ids.add(s.submit("u" + i, "msg " + k).id());
        });
        assertEquals(3200, ids.size());
        assertEquals(3200, s.byStatus(Status.PENDING).size());
    }
}
