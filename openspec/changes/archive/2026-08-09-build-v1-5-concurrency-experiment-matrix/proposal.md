## Why

FlashFlow V1 proves its core MySQL concurrency invariants, but its HTTP evidence is a single saturated local run in which 6,689 of 9,357 responses were retryable 503s. A controlled experiment matrix is needed to distinguish strategy conflicts, bounded retry exhaustion, connection-pool pressure, and sold-out business outcomes without turning one local request-rate number into a production-capacity claim.

## What Changes

- Add a reproducible, one-factor-at-a-time experiment matrix for inventory strategy, request concurrency, connection-pool size, retry budget, stock level, and SKU contention shape.
- Classify and measure committed business results separately from technical contention, retry exhaustion, connection-pool pressure, and unexpected failures.
- Record run configuration, environment, latency distribution, retry/lock evidence, result counts, and committed-state invariant queries in a stable per-run report format.
- Require correctness gates before characterization runs and preserve `PASS`, `FAIL`, `BLOCKED`, and `NOT_RUN` as evidence states.
- Add direct regression evidence for post-expiration reorder and bounded optimistic-conflict exhaustion where the V1 scenario matrix currently relies on indirect evidence.
- Keep the synchronous ordering contract, MySQL source of truth, bounded whole-transaction retry, and existing inventory schema unchanged.
- Exclude Redis, messaging, automated refunds, microservice extraction, and production-capacity or high-availability claims.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `concurrency-experiments`: Strengthen the experiment contract with controlled matrix execution, contention-source observability, stable evidence artifacts, and explicit preconditions for performance characterization.

## Impact

- Affects the concurrency experiment runner and k6 workload configuration, Micrometer instrumentation around ordering attempts and contention outcomes, database invariant/reporting queries, test coverage, and verification documentation.
- May add local experiment orchestration scripts or configuration profiles, but introduces no new production data store or message broker.
- Does not change the public ordering API or the legal order, reservation, payment, and expiration state transitions.
