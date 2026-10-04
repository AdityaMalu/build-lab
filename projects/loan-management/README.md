# Loan Lifecycle & Balance Management

**Scenario.** A lending startup's in-memory loan ledger is misbehaving: customers have been able to repay more than
they owe (ending with negative balances), loans that hit zero don't close, and under load the balance-transfer
endpoint occasionally **hangs forever**. The service in your workspace is the production code. Repair it.

## Domain (package `loans`)
All money is in **cents** (`long`). Never use `double` for money.

States: `PENDING → ACTIVE → PAID`, plus `PENDING → CANCELLED`.

| Method | Contract |
|---|---|
| `String create(String borrower, long principalCents)` | Blank borrower or principal ≤ 0 → `IllegalArgumentException`. New loan is `PENDING`, balance = principal. Ids `"L1"`, `"L2"`, ... |
| `void fund(String id)` | Only from `PENDING` → `ACTIVE`, else `IllegalStateException`. |
| `void cancel(String id)` | Only from `PENDING` → `CANCELLED`, else `IllegalStateException`. |
| `void repay(String id, long amountCents)` | Only `ACTIVE` loans (`IllegalStateException`). Amount must be > 0 and ≤ balance (`IllegalArgumentException`). When the balance reaches exactly 0 the loan becomes `PAID`. |
| `void transfer(String fromId, String toId, long amountCents)` | Moves outstanding debt from one loan to another. Both must be `ACTIVE`, belong to the **same borrower**, be different loans; `0 < amount ≤ from.balance`. If `from` reaches 0 it becomes `PAID`. Must be **atomic** (no one may observe money missing or duplicated) and **deadlock-free** even when two threads transfer A→B and B→A at the same time. |
| `LoanView view(String id)` | Snapshot (id, borrower, balance, status). Unknown ids anywhere → `NoSuchElementException`. |
| `long totalOutstanding(String borrower)` | Sum of balances of that borrower's `ACTIVE` loans. |

Every method can be called concurrently. Concurrent repayments must never lose updates or overdraw a loan.

## Debugging tips
- Lock ordering is the classic fix for A→B / B→A deadlocks (always lock the lower id first).
- Check each state transition against the table.
- Read every validation for off-by-one and wrong-direction comparisons.
