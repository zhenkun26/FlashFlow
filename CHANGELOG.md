# Changelog

FlashFlow follows evidence-backed milestone releases. Performance figures below describe controlled local Docker experiments only; they are not production capacity or availability claims.

## V1.6 — 2026-08-09

### Changed

- Reordered safe order creation so the selected reservation strategy establishes control of the stock row before inserting stock-referencing order, claim, reservation, and movement rows.
- Added a non-locking effective-claim precheck and a locking current-read recheck on the sold-out path to preserve `EXISTING_EFFECTIVE_ORDER` precedence under MySQL `REPEATABLE READ`.
- Added bounded whole-transaction claim-race rollback and replay outcomes, distinct from optimistic conflict and transient database retry.
- Restricted the legacy child-first transaction sequence to the `lab` profile for controlled before/after experiments.

### Evidence

- Added deterministic MySQL tests that reproduce the former foreign-key shared-to-exclusive lock-upgrade deadlock and verify the stock-first control preserves all committed invariants.
- Full correctness suite: 37 tests passed with zero failures, errors, or skips.
- Controlled hot-SKU comparison: the child-first case recorded 4,598 transient database retries and 927 exhausted requests; the stock-first case recorded zero for both, while each committed exactly 100 valid orders.
- Archived OpenSpec change: `2026-08-09-reduce-ordering-fk-lock-upgrade-deadlocks`.

## V1.5 — 2026-08-09

### Added

- Added a schema-validated experiment manifest and one-factor comparison matrix for strategy, concurrency, connection pool, retry budget, stock level, and SKU contention shape.
- Added repeatable orchestration for correctness gates, disposable dataset preparation, k6 execution, metrics capture, committed-state invariant checks, and append-only evidence reports.
- Added explicit contention, retry-exhaustion, connection-pool, and unexpected-failure classifications without high-cardinality metric tags.

### Evidence

- Seven canonical local cases completed with reconciled HTTP outcomes, zero unexpected responses, and valid committed inventory/order state.
- Added dated V1.5 verification and experiment-reproduction documentation.
- Archived OpenSpec change: `2026-08-09-build-v1-5-concurrency-experiment-matrix`.

## V1 — 2026-08-08

- Established the synchronous Java/MySQL ordering laboratory with durable idempotency, one-effective-order claims, inventory reservation, payment, expiration, compensation records, Testcontainers correctness tests, and bounded whole-transaction retry.
