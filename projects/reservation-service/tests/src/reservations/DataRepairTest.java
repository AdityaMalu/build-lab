package reservations;

import java.time.LocalDate;
import java.util.List;

import testkit.Test;

import static testkit.Assert.*;

public class DataRepairTest {

    static Reservation r(String id, String room, String guest, int in, int out, long seq, Status st) {
        return new Reservation(id, room, guest, LocalDate.of(2026, 5, in), LocalDate.of(2026, 5, out), seq, st);
    }

    @Test("clean data passes through unchanged, sorted by seq")
    public void clean() {
        Reservation a = r("R2", "1", "zed", 1, 3, 2, Status.ACTIVE);
        Reservation b = r("R1", "1", "amy", 3, 5, 1, Status.ACTIVE);
        RepairResult res = DataRepair.repair(List.of(a, b));
        assertEquals(List.of(b, a), res.rows());
        assertTrue(res.cancelledIds().isEmpty());
    }

    @Test("earliest booking (lowest seq) wins, not alphabetical guest")
    public void earliestWins() {
        Reservation early = r("R1", "1", "zoe", 1, 5, 1, Status.ACTIVE);
        Reservation late = r("R2", "1", "adam", 3, 7, 2, Status.ACTIVE);
        RepairResult res = DataRepair.repair(List.of(late, early));
        assertEquals(List.of("R2"), res.cancelledIds());
        assertEquals(Status.ACTIVE, res.rows().get(0).status());
        assertEquals("R1", res.rows().get(0).id());
    }

    @Test("losers are kept as CANCELLED rows, not dropped")
    public void losersKept() {
        Reservation a = r("R1", "1", "a", 1, 5, 1, Status.ACTIVE);
        Reservation b = r("R2", "1", "b", 2, 3, 2, Status.ACTIVE);
        Reservation c = r("R3", "1", "c", 4, 8, 3, Status.ACTIVE);
        RepairResult res = DataRepair.repair(List.of(a, b, c));
        assertEquals(3, res.rows().size(), "no row may disappear");
        assertEquals(Status.CANCELLED, res.rows().get(1).status());
        assertEquals(Status.CANCELLED, res.rows().get(2).status());
        assertEquals(List.of("R2", "R3"), res.cancelledIds());
    }

    @Test("duplicate ids collapse to one row")
    public void duplicates() {
        Reservation a = r("R1", "1", "a", 1, 5, 1, Status.ACTIVE);
        Reservation dup = r("R1", "1", "a", 1, 5, 1, Status.ACTIVE);
        Reservation other = r("R2", "2", "b", 1, 5, 2, Status.ACTIVE);
        RepairResult res = DataRepair.repair(List.of(a, other, dup));
        assertEquals(2, res.rows().size(), "duplicate must not be counted as an overlap");
        assertTrue(res.cancelledIds().isEmpty(), "a duplicate is not a conflicting booking");
    }

    @Test("already-cancelled rows neither block nor get re-cancelled")
    public void cancelledRows() {
        Reservation old = r("R1", "1", "a", 1, 9, 1, Status.CANCELLED);
        Reservation now = r("R2", "1", "b", 2, 4, 2, Status.ACTIVE);
        RepairResult res = DataRepair.repair(List.of(old, now));
        assertTrue(res.cancelledIds().isEmpty());
        assertEquals(Status.CANCELLED, res.rows().get(0).status());
        assertEquals(Status.ACTIVE, res.rows().get(1).status());
    }

    @Test("rooms are independent; back-to-back is not a conflict")
    public void perRoom() {
        Reservation a = r("R1", "1", "a", 1, 3, 1, Status.ACTIVE);
        Reservation b = r("R2", "2", "b", 1, 3, 2, Status.ACTIVE);
        Reservation c = r("R3", "1", "c", 3, 6, 3, Status.ACTIVE);
        RepairResult res = DataRepair.repair(List.of(a, b, c));
        assertTrue(res.cancelledIds().isEmpty());
    }
}
