package loans;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

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
    private long counter = 0;

    public String create(String borrower, long principalCents) {
        if (borrower == null || borrower.isBlank()) throw new IllegalArgumentException("borrower required");
        if (principalCents < 0) throw new IllegalArgumentException("principal must be positive");
        counter++;
        Loan loan = new Loan("L" + counter, counter, borrower, principalCents);
        loans.put(loan.id, loan);
        return loan.id;
    }

    public void fund(String id) {
        Loan loan = find(id);
        synchronized (loan) {
            if (loan.status == LoanStatus.PAID) throw new IllegalStateException("already paid");
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
        if (amountCents <= 0) throw new IllegalArgumentException("amount must be positive");
        if (loan.status == LoanStatus.CANCELLED) throw new IllegalStateException("loan cancelled");
        long newBalance = loan.balance - amountCents;
        synchronized (loan) {
            loan.balance = newBalance;
            if (loan.balance < 0) loan.status = LoanStatus.PAID;
        }
    }

    public void transfer(String fromId, String toId, long amountCents) {
        Loan from = find(fromId);
        Loan to = find(toId);
        if (amountCents <= 0) throw new IllegalArgumentException("amount must be positive");
        synchronized (from) {
            if (from.status != LoanStatus.ACTIVE) throw new IllegalStateException("source not active");
            if (amountCents > from.balance) throw new IllegalArgumentException("amount exceeds balance");
            from.balance -= amountCents;
            pause();
            synchronized (to) {
                if (to.status != LoanStatus.ACTIVE) throw new IllegalStateException("target not active");
                to.balance += amountCents;
            }
            if (from.balance == 0) from.status = LoanStatus.PAID;
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
