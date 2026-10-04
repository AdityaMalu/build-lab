# Return Risk Assessment

**Scenario.** The returns team scores every return request so that risky ones get a manual review. The scoring
engine was written quickly and the risk team says the scores don't match their rulebook. Make the engine match the
spec **exactly**, including boundaries, and make the batch API robust to bad rows.

## The rulebook (package `returns`)
Input: `ReturnRequest(String orderId, long amountCents, int daysSincePurchase, int returnsLast90Days,
Category category, boolean opened, boolean hasReceipt)`.

Add points:

| Signal | Points |
|---|---|
| `daysSincePurchase` **> 60** | +40 |
| else `daysSincePurchase` **> 30** | +25 (not cumulative with the line above) |
| `amountCents` **≥ 100 000** ($1000) | +35 |
| else `amountCents` **≥ 50 000** ($500) | +20 |
| `returnsLast90Days` **≥ 6** | +30 |
| else `returnsLast90Days` **≥ 3** | +15 |
| `opened` **and** category is `ELECTRONICS` | +10 |
| no receipt | +20 |

- The score is **capped at 100**.
- Level: score **< 30** → `LOW`, **30–59** → `MEDIUM`, **≥ 60** → `HIGH`.
- `reasons` lists the rule codes that fired, in the table order:
  `LATE_RETURN`, `HIGH_VALUE`, `FREQUENT_RETURNER`, `OPENED_ELECTRONICS`, `NO_RECEIPT`.
  (`LATE_RETURN`, `HIGH_VALUE` and `FREQUENT_RETURNER` each appear once even for the higher tier.)

## Validation
`assess` throws `IllegalArgumentException` when: request is null, orderId blank, amount < 0, days < 0,
returns < 0, or category null.

## Batch
`List<BatchEntry> assessBatch(List<ReturnRequest> requests)`: one entry per input, same order.
A bad row produces `BatchEntry.error(orderId, message)` (orderId may be null) and must not stop the batch.

## How to approach it
Run the tests, then compare each rule in the code against the table above. Bugs at boundaries (`>` vs `>=`) are
the classic interview trap. Write down the boundary values before you touch the code.
