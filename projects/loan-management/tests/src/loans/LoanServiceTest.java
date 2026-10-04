package loans;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import testkit.Concurrent;
import testkit.Test;

import static testkit.Assert.*;

public class LoanServiceTest {

    static String active(LoanService s, String borrower, long cents) {
        String id = s.create(borrower, cents);
        s.fund(id);
        return id;
    }

    @Test("create validates borrower and principal")
    public void createValidation() {
        LoanService s = new LoanService();
        assertThrows(IllegalArgumentException.class, () -> s.create(" ", 100));
        assertThrows(IllegalArgumentException.class, () -> s.create("ana", 0), "zero principal");
        assertThrows(IllegalArgumentException.class, () -> s.create("ana", -5));
        String id = s.create("ana", 10_000);
        assertEquals("L1", id);
        assertEquals(new LoanView("L1", "ana", 10_000, LoanStatus.PENDING), s.view(id));
    }

    @Test("state machine: fund only from PENDING, cancel only from PENDING")
    public void stateMachine() {
        LoanService s = new LoanService();
        String a = s.create("ana", 500);
        s.fund(a);
        assertEquals(LoanStatus.ACTIVE, s.view(a).status());
        assertThrows(IllegalStateException.class, () -> s.fund(a), "double funding");
        assertThrows(IllegalStateException.class, () -> s.cancel(a), "cannot cancel an active loan");

        String b = s.create("ana", 500);
        s.cancel(b);
        assertThrows(IllegalStateException.class, () -> s.fund(b), "a cancelled loan must stay cancelled");
        assertEquals(LoanStatus.CANCELLED, s.view(b).status());
    }

    @Test("repay only active loans")
    public void repayState() {
        LoanService s = new LoanService();
        String pending = s.create("ana", 500);
        assertThrows(IllegalStateException.class, () -> s.repay(pending, 100), "pending loan");
        assertEquals(500L, s.view(pending).balanceCents(), "nothing changed");
    }

    @Test("repay validates amount and never overdraws")
    public void repayAmount() {
        LoanService s = new LoanService();
        String id = active(s, "ana", 1_000);
        assertThrows(IllegalArgumentException.class, () -> s.repay(id, 0));
        assertThrows(IllegalArgumentException.class, () -> s.repay(id, 1_001), "overpayment");
        assertEquals(1_000L, s.view(id).balanceCents(), "rejected repayment changes nothing");
        s.repay(id, 400);
        assertEquals(600L, s.view(id).balanceCents());
    }

    @Test("loan becomes PAID exactly at zero")
    public void paidAtZero() {
        LoanService s = new LoanService();
        String id = active(s, "ana", 1_000);
        s.repay(id, 999);
        assertEquals(LoanStatus.ACTIVE, s.view(id).status());
        s.repay(id, 1);
        assertEquals(LoanStatus.PAID, s.view(id).status());
        assertEquals(0L, s.view(id).balanceCents());
        assertThrows(IllegalStateException.class, () -> s.repay(id, 1), "paid loans take no more payments");
    }

    @Test("concurrent repayments lose no updates and never overdraw")
    public void concurrentRepay() throws Exception {
        LoanService s = new LoanService();
        String id = active(s, "ana", 10_000);
        AtomicInteger ok = new AtomicInteger();
        Concurrent.run(20, i -> {
            for (int k = 0; k < 100; k++) {
                try {
                    s.repay(id, 7);
                    ok.incrementAndGet();
                } catch (IllegalArgumentException | IllegalStateException rejected) {
                    // overpay or already paid
                }
            }
        });
        LoanView v = s.view(id);
        assertEquals(10_000L - 7L * ok.get(), v.balanceCents(), "balance must match accepted repayments");
        assertTrue(v.balanceCents() >= 0, "never negative");
        assertEquals(1428, ok.get(), "10000 / 7 = 1428 payments fit");
    }

    @Test("transfer moves debt between a borrower's loans")
    public void transferBasic() {
        LoanService s = new LoanService();
        String a = active(s, "ana", 1_000);
        String b = active(s, "ana", 500);
        s.transfer(a, b, 300);
        assertEquals(700L, s.view(a).balanceCents());
        assertEquals(800L, s.view(b).balanceCents());
        s.transfer(a, b, 700);
        assertEquals(LoanStatus.PAID, s.view(a).status());
        assertEquals(1_500L, s.totalOutstanding("ana"));
    }

    @Test("invalid transfers change nothing")
    public void transferValidation() {
        LoanService s = new LoanService();
        String a = active(s, "ana", 1_000);
        String b = active(s, "ana", 500);
        String other = active(s, "ben", 500);
        String pending = s.create("ana", 100);
        assertThrows(IllegalArgumentException.class, () -> s.transfer(a, other, 10), "different borrower");
        assertThrows(IllegalArgumentException.class, () -> s.transfer(a, a, 10), "same loan");
        assertThrows(IllegalArgumentException.class, () -> s.transfer(a, b, 1_001), "more than balance");
        assertThrows(IllegalArgumentException.class, () -> s.transfer(a, b, 0));
        assertThrows(IllegalStateException.class, () -> s.transfer(a, pending, 10), "target pending");
        assertThrows(NoSuchElementException.class, () -> s.transfer(a, "L99", 10));
        assertEquals(1_000L, s.view(a).balanceCents(), "source untouched after failed transfers");
        assertEquals(500L, s.view(b).balanceCents());
        assertEquals(100L, s.view(pending).balanceCents());
    }

    @Test(value = "opposite-direction transfers do not deadlock and conserve money", timeoutMillis = 15_000)
    public void transferDeadlock() throws Exception {
        LoanService s = new LoanService();
        String a = active(s, "ana", 1_000_000);
        String b = active(s, "ana", 1_000_000);
        Concurrent.run(8, i -> {
            for (int k = 0; k < 150; k++) {
                if (i % 2 == 0) s.transfer(a, b, 3);
                else s.transfer(b, a, 3);
            }
        });
        assertEquals(2_000_000L, s.view(a).balanceCents() + s.view(b).balanceCents(), "money conserved");
        assertEquals(2_000_000L, s.totalOutstanding("ana"));
    }

    @Test("totalOutstanding counts only ACTIVE loans of that borrower")
    public void outstanding() {
        LoanService s = new LoanService();
        active(s, "ana", 100);
        active(s, "ana", 250);
        s.create("ana", 999); // pending
        active(s, "ben", 5);
        String paid = active(s, "ana", 10);
        s.repay(paid, 10);
        assertEquals(350L, s.totalOutstanding("ana"));
        assertEquals(0L, s.totalOutstanding("nobody"));
    }

    @Test("ids are unique under concurrent creation")
    public void uniqueIds() throws Exception {
        LoanService s = new LoanService();
        java.util.Set<String> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Concurrent.run(16, i -> {
            for (int k = 0; k < 200; k++) ids.add(s.create("u" + i, 1));
        });
        assertEquals(3200, ids.size());
    }
}
