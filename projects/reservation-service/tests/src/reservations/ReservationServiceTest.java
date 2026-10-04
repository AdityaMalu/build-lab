package reservations;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import testkit.Concurrent;
import testkit.Test;

import static testkit.Assert.*;

public class ReservationServiceTest {

    static LocalDate d(int day) {
        return LocalDate.of(2026, 3, day);
    }

    static ReservationService svc() {
        return new ReservationService(Set.of("101", "102"));
    }

    @Test("basic reservation gets id R1 and is active")
    public void basic() {
        ReservationService s = svc();
        Reservation r = s.reserve("101", "ana", d(1), d(3));
        assertEquals("R1", r.id());
        assertEquals(Status.ACTIVE, r.status());
        assertEquals(List.of(r), s.activeForRoom("101"));
    }

    @Test("back-to-back stays are allowed (checkout day = next checkin day)")
    public void backToBack() {
        ReservationService s = svc();
        s.reserve("101", "ana", d(1), d(3));
        assertTrue(s.isAvailable("101", d(3), d(5)), "isAvailable should allow back-to-back");
        Reservation next = s.reserve("101", "ben", d(3), d(5));
        assertEquals("R2", next.id());
        Reservation before = s.reserve("101", "cy", d(1).minusDays(2), d(1));
        assertEquals(Status.ACTIVE, before.status());
    }

    @Test("overlapping stays are rejected")
    public void overlap() {
        ReservationService s = svc();
        s.reserve("101", "ana", d(5), d(10));
        assertThrows(IllegalStateException.class, () -> s.reserve("101", "x", d(4), d(6)));
        assertThrows(IllegalStateException.class, () -> s.reserve("101", "x", d(9), d(12)));
        assertThrows(IllegalStateException.class, () -> s.reserve("101", "x", d(6), d(7)));
        assertThrows(IllegalStateException.class, () -> s.reserve("101", "x", d(1), d(20)));
        assertNotNull(s.reserve("102", "x", d(5), d(10)), "another room is unaffected");
    }

    @Test("validation")
    public void validation() {
        ReservationService s = svc();
        assertThrows(IllegalArgumentException.class, () -> s.reserve("999", "a", d(1), d(2)));
        assertThrows(IllegalArgumentException.class, () -> s.reserve("101", " ", d(1), d(2)));
        assertThrows(IllegalArgumentException.class, () -> s.reserve("101", "a", null, d(2)));
        assertThrows(IllegalArgumentException.class, () -> s.reserve("101", "a", d(3), d(2)));
        assertThrows(IllegalArgumentException.class, () -> s.reserve("101", "a", d(2), d(2)),
                "zero-night stay is invalid");
    }

    @Test("cancel frees the room and is visible everywhere")
    public void cancelFrees() {
        ReservationService s = svc();
        Reservation r = s.reserve("101", "ana", d(1), d(4));
        s.cancel(r.id());
        assertEquals(Status.CANCELLED, s.get(r.id()).status());
        assertTrue(s.activeForRoom("101").isEmpty(), "cancelled stay must not be listed as active");
        assertTrue(s.isAvailable("101", d(2), d(3)), "dates must be free after cancel");
        assertNotNull(s.reserve("101", "ben", d(1), d(4)));
        s.cancel(r.id()); // no-op
        assertThrows(NoSuchElementException.class, () -> s.cancel("R404"));
    }

    @Test("activeForRoom is sorted by check-in")
    public void sorted() {
        ReservationService s = svc();
        s.reserve("101", "c", d(20), d(22));
        s.reserve("101", "a", d(1), d(2));
        s.reserve("101", "b", d(10), d(12));
        List<Reservation> list = s.activeForRoom("101");
        assertEquals(d(1), list.get(0).checkIn());
        assertEquals(d(10), list.get(1).checkIn());
        assertEquals(d(20), list.get(2).checkIn());
    }

    @Test("no double booking under concurrent traffic")
    public void concurrency() throws Exception {
        for (int round = 0; round < 20; round++) {
            ReservationService s = svc();
            AtomicInteger wins = new AtomicInteger();
            Concurrent.run(32, i -> {
                try {
                    s.reserve("101", "guest" + i, d(10), d(12));
                    wins.incrementAndGet();
                } catch (IllegalStateException expected) {
                    // lost the race, fine
                }
            });
            assertEquals(1, wins.get(), "round " + round + ": exactly one guest may get the room");
            assertEquals(1, s.activeForRoom("101").size(), "round " + round);
        }
    }

    @Test("ids stay unique under concurrency")
    public void uniqueIds() throws Exception {
        ReservationService s = new ReservationService(Set.of("A"));
        Concurrent.run(16, i -> {
            for (int k = 0; k < 50; k++) {
                int day = i * 50 + k; // disjoint single nights
                LocalDate in = LocalDate.of(2027, 1, 1).plusDays(day);
                s.reserve("A", "g", in, in.plusDays(1));
            }
        });
        List<Reservation> all = s.activeForRoom("A");
        assertEquals(800, all.size());
        assertEquals(800L, (Object) all.stream().map(Reservation::id).distinct().count());
    }
}
