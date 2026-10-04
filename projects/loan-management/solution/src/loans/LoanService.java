package loans;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixes vs. the production version:
 *  - create: principal must be > 0 (was >= 0); id counter is atomic.
 *  - fund: only PENDING -> ACTIVE (it re-activated cancelled loans).
 *  - repay: validate state, amount <= balance, and compute the new balance inside the lock
 *    (the read-modify-write was outside the lock and lost updates); PAID when balance == 0.
 *  - transfer: lock both loans in a global order (by loan number) to avoid deadlock, validate
 *    everything before mutating so a failed transfer changes nothing, same borrower, distinct loans.
 */
public class LoanService {

    static final class Loan {
        final String id;
        final long number;
        final String borrower;
        long balance;
        LoanStatus status = LoanStatus.PENDING;

        Loan(String id, long number, String borrower, long balance) {
            this.id = id;
            this.number = number;
            this.borrower = borrower;
            this.balance = balance;
        }
    }

    private final Map<String, Loan> loans = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    public String create(String borrower, long principalCents) {
        if (borrower == null || borrower.isBlank()) throw new IllegalArgumentException("borrower required");
        if (principalCents <= 0) throw new IllegalArgumentException("principal must be positive");
        long n = counter.incrementAndGet();
        Loan loan = new Loan("L" + n, n, borrower, principalCents);
        loans.put(loan.id, loan);
        return loan.id;
    }

    public void fund(String id) {
        Loan loan = find(id);
        synchronized (loan) {
            if (loan.status != LoanStatus.PENDING) throw new IllegalStateException("only pending loans can be funded");
            loan.status = LoanStatus.ACTIVE;
        }
    }

    public void cancel(String id) {
        Loan loan = find(id);
        synchronized (loan) {
            if (loan.status != LoanStatus.PENDING) throw new IllegalStateException("only pending loans can be cancelled");
            loan.status = LoanStatus.CANCELLED;
        }
    }

    public void repay(String id, long amountCents) {
        Loan loan = find(id);
        synchronized (loan) {
            if (loan.status != LoanStatus.ACTIVE) throw new IllegalStateException("loan not active");
            if (amountCents <= 0) throw new IllegalArgumentException("amount must be positive");
            if (amountCents > loan.balance) throw new IllegalArgumentException("amount exceeds balance");
            loan.balance -= amountCents;
            if (loan.balance == 0) loan.status = LoanStatus.PAID;
        }
    }

    public void transfer(String fromId, String toId, long amountCents) {
        Loan from = find(fromId);
        Loan to = find(toId);
        if (from == to) throw new IllegalArgumentException("cannot transfer to the same loan");
        if (amountCents <= 0) throw new IllegalArgumentException("amount must be positive");
        Loan first = from.number < to.number ? from : to;
        Loan second = first == from ? to : from;
        synchronized (first) {
            synchronized (second) {
                if (from.status != LoanStatus.ACTIVE) throw new IllegalStateException("source not active");
                if (to.status != LoanStatus.ACTIVE) throw new IllegalStateException("target not active");
                if (!from.borrower.equals(to.borrower)) throw new IllegalArgumentException("different borrowers");
                if (amountCents > from.balance) throw new IllegalArgumentException("amount exceeds balance");
                from.balance -= amountCents;
                pause();
                to.balance += amountCents;
                if (from.balance == 0) from.status = LoanStatus.PAID;
            }
        }
    }

    public LoanView view(String id) {
        Loan loan = find(id);
        synchronized (loan) {
            return new LoanView(loan.id, loan.borrower, loan.balance, loan.status);
        }
    }

    public long totalOutstanding(String borrower) {
        long total = 0;
        for (Loan loan : loans.values()) {
            synchronized (loan) {
                if (loan.borrower.equals(borrower) && loan.status == LoanStatus.ACTIVE) total += loan.balance;
            }
        }
        return total;
    }

    private Loan find(String id) {
        Loan loan = id == null ? null : loans.get(id);
        if (loan == null) throw new NoSuchElementException("no loan " + id);
        return loan;
    }

    /** Simulates the latency of the audit-log write that production does mid-transfer. */
    private static void pause() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
