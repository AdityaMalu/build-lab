# Return Risk Assessment (Python)

**Scenario.** The returns team scores every return request so that risky ones get a manual review. The scoring
engine was written quickly and the risk team says the scores don't match their rulebook. Make the engine match the
spec **exactly**, including boundaries, and make the batch API robust to bad rows.

## The rulebook (package `returns`)
Input: `ReturnRequest(order_id, amount_cents, days_since_purchase, returns_last_90_days, category, opened,
has_receipt)`, with `category` a `Category` enum value.

| Signal | Points |
|---|---|
| `days_since_purchase` **> 60** | +40 |
| else `days_since_purchase` **> 30** | +25 (not cumulative with the line above) |
| `amount_cents` **≥ 100 000** ($1000) | +35 |
| else `amount_cents` **≥ 50 000** ($500) | +20 |
| `returns_last_90_days` **≥ 6** | +30 |
| else `returns_last_90_days` **≥ 3** | +15 |
| `opened` **and** category is `ELECTRONICS` | +10 |
| no receipt | +20 |

- The score is **capped at 100**.
- Level: score **< 30** → `RiskLevel.LOW`, **30–59** → `MEDIUM`, **≥ 60** → `HIGH`.
- `RiskResult.reasons` is a **tuple** of rule codes that fired, in table order:
  `LATE_RETURN`, `HIGH_VALUE`, `FREQUENT_RETURNER`, `OPENED_ELECTRONICS`, `NO_RECEIPT`
  (each appears once, even for the higher tier).

## Validation
`assess` raises `ValueError` when: the request is `None`, `order_id` is blank, the amount, days or returns count is
negative, or `category` isn't a `Category`.

## Batch
`assess_batch(requests) -> list[BatchEntry]`: one entry per input, same order.
A bad row produces `BatchEntry(order_id, None, error_message)` (`order_id` is `None` for a `None` row) and must not
stop the batch.

## How to approach it
Run the tests, then compare each rule in the code against the table above. Write down the boundary values
(`>` vs `>=`) before you touch the code.
