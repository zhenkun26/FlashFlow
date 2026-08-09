## Why

V1.5 preserved all business invariants but captured a repeatable InnoDB cycle in which concurrent order transactions first held shared foreign-key locks on the hot stock row and then both attempted to upgrade that row to an exclusive update lock. Bounded whole-transaction retry keeps the system correct, but the resulting retry exhaustion dominates the single-hot-SKU characterization, so the root cause should be proved deterministically and the lock order corrected before adding infrastructure or raising retry budgets.

## What Changes

- Add a deterministic database test that reproduces and identifies the foreign-key shared-lock to stock exclusive-lock upgrade cycle without relying on random load timing.
- Define one stock-first lock protocol for the safe ordering transaction so foreign-key child inserts do not precede exclusive control of the referenced stock row.
- Preserve the existing synchronous HTTP contract, idempotency semantics, one-effective-order rule, bounded whole-transaction retry, and atomic order/claim/reservation/movement effects.
- Define explicit behavior for a same-user claim discovered around stock acquisition so `EXISTING_EFFECTIVE_ORDER` remains stable without a committed inventory effect.
- Compare the current and candidate transaction sequences under the existing controlled matrix and retain deadlock, retry, outcome, latency, and committed-state evidence separately.
- Do not remove foreign keys, split the order effect across transactions, add distributed locks, or treat a larger retry budget as the fix.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `concurrency-experiments`: Require deterministic proof of the foreign-key lock-upgrade cycle, a consistent stock-first protocol for safe ordering, and controlled before/after evidence that preserves existing invariants and response semantics.

## Impact

- Ordering transaction sequencing in `OrderApplicationService` and the inventory strategy boundary.
- MyBatis ordering/inventory queries used to acquire stock and resolve purchase claims.
- Deterministic MySQL integration tests, transaction-boundary documentation, and the V1.5 experiment manifest/reporting workflow.
- No public endpoint, response-code, persistent business-table, Redis, MQ, or external service change.
