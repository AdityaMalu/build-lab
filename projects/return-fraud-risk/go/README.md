# Return Risk Assessment (Go)

**Scenario.** The returns team scores every return request so that risky ones get a manual review. The scoring
engine was written quickly and the risk team says the scores don't match their rulebook. Make the engine match the
spec **exactly**, including boundaries, and make the batch API robust to bad rows.

## The rulebook (package `returns`)
`Assess(r *ReturnRequest) (RiskResult, error)` and `AssessBatch(requests []*ReturnRequest) []BatchEntry`.

| Signal | Points |
|---|---|
| `DaysSincePurchase` **> 60** | +40 |
| else `DaysSincePurchase` **> 30** | +25 (not cumulative with the line above) |
| `AmountCents` **≥ 100 000** ($1000) | +35 |
| else `AmountCents` **≥ 50 000** ($500) | +20 |
| `ReturnsLast90Days` **≥ 6** | +30 |
| else `ReturnsLast90Days` **≥ 3** | +15 |
| `Opened` **and** category is `Electronics` | +10 |
| no receipt | +20 |

- The score is **capped at 100**.
- Level: score **< 30** → `Low`, **30–59** → `Medium`, **≥ 60** → `High`.
- `Reasons` lists the rule codes that fired, in table order:
  `LATE_RETURN`, `HIGH_VALUE`, `FREQUENT_RETURNER`, `OPENED_ELECTRONICS`, `NO_RECEIPT`
  (each appears once, even for the higher tier).

## Validation
`Assess` returns an error wrapping `ErrInvalidRequest` when: the request is `nil`, `OrderID` is blank, the amount,
days or returns count is negative, or `Category` isn't one of the defined constants (the zero value is invalid).

## Batch
One `BatchEntry` per input, same order. A bad row gets `Err` set (and `Result` nil, `OrderID` empty for a `nil`
row) and must not stop the batch.

## How to approach it
Run the tests, then compare each rule in the code against the table above. Write down the boundary values
(`>` vs `>=`) before you touch the code.
