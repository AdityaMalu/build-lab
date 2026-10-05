# Return Risk Assessment (C++)

**Scenario.** The returns team scores every return request so that risky ones get a manual review. The scoring
engine was written quickly and the risk team says the scores don't match their rulebook. Make the engine match the
spec **exactly**, including boundaries, and make the batch API robust to bad rows.

## The rulebook (header `return_risk.hpp`)
`ReturnRiskEngine::assess(const ReturnRequest&) const -> RiskResult` and
`ReturnRiskEngine::assessBatch(const std::vector<std::optional<ReturnRequest>>&) const -> std::vector<BatchEntry>`.

| Signal | Points |
|---|---|
| `daysSincePurchase` **> 60** | +40 |
| else `daysSincePurchase` **> 30** | +25 (not cumulative with the line above) |
| `amountCents` **≥ 100 000** ($1000) | +35 |
| else `amountCents` **≥ 50 000** ($500) | +20 |
| `returnsLast90Days` **≥ 6** | +30 |
| else `returnsLast90Days` **≥ 3** | +15 |
| `opened` **and** category is `Category::Electronics` | +10 |
| no receipt | +20 |

- The score is **capped at 100**.
- Level: score **< 30** → `RiskLevel::Low`, **30–59** → `Medium`, **≥ 60** → `High`.
- `reasons` lists the rule codes that fired, in table order:
  `LATE_RETURN`, `HIGH_VALUE`, `FREQUENT_RETURNER`, `OPENED_ELECTRONICS`, `NO_RECEIPT`
  (each appears once, even for the higher tier).

## Validation
`assess` throws `std::invalid_argument` when: `orderId` is blank, the amount, days or returns count is negative, or
`category` is empty (`std::nullopt`).

## Batch
One `BatchEntry` per input, same order. An empty (`std::nullopt`) or invalid row gets an `error` message, no
`result`, and its `orderId` (none for an empty row). It must not stop the batch.

## How to approach it
Run the tests, then compare each rule in the code against the table above. Write down the boundary values
(`>` vs `>=`) before you touch the code.
