package loans;

public record LoanView(String id, String borrower, long balanceCents, LoanStatus status) {
}
