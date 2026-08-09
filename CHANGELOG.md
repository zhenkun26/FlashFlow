# Changelog

FlashFlow follows evidence-backed milestone releases. Performance figures below describe controlled local Docker experiments only; they are not production capacity or availability claims.

## V2.1 — 2026-08-09

### Added

- Added a versioned, transport-neutral order-command envelope, privacy-preserving stable command identity, durable command ledger, and idempotent in-process consumer seam.
- Added explicit publication ambiguity decisions and a disabled future `202 Accepted`/command-status contract without adding public V3 routes.
- Unified delayed expiration triggers and the existing database scanner behind one locked, duplicate-safe closure boundary.
- Added an isolated, pinned RocketMQ 5.3.4 compatibility profile, manifest fields, synthetic fault harness, reconciled readiness reports, and bounded command metrics.

### Boundary

- The normal application remains synchronous and contains no live RocketMQ client, producer, consumer, dispatcher, Transactional Outbox, CDC, or automatic fail-open path.
- A command-ledger row is not evidence that a message was published. Synthetic fault tests are not broker reliability, delay-SLA, throughput, or production-readiness evidence.
- V3 implementation remains a separate OpenSpec change; publication status is recorded independently from the source changes.

### OpenSpec

- All six capability deltas were synchronized to the main specifications after the revision-bound 80-test, synchronous compatibility, manifest, RocketMQ, and strict OpenSpec gates passed.
- Archived change: `2026-08-09-build-v2-1-v3-readiness-foundation`.

## V2 — 2026-08-09

### Added

- Added an explicitly selected Redis Lua admission mode with privacy-preserving identities, bounded per-SKU generation state, replay, per-user activity, capacity, confirmation, safe release, and quarantine transitions.
- Added fail-closed ordering orchestration, durable MySQL replay before Redis, and after-commit unpaid-closure release while preserving the V1 stock-first MySQL transaction.
- Added fenced MySQL-authoritative generation reconciliation and append-only reports.
- Extended Testcontainers, bounded metrics, and the experiment manifest/runner with Redis runtime, script, generation, lifecycle, and reconciliation evidence.

### Boundary

- MySQL remains authoritative; V2 adds no MQ, Outbox, CDC, distributed lock, automatic fail-open behavior, production availability, or production capacity claim.
- Final full-suite, fault-drill, canonical comparison, OpenSpec sync/archive, and publication status are recorded separately and are not implied by source changes.

### OpenSpec

- Synced all five capability deltas to the main specifications and archived `add-redis-lua-admission-control` as `2026-08-09-add-redis-lua-admission-control` after all 46 tasks and strict validation passed.
- Released on `main` after the full 60-test MySQL/Redis suite and all six main OpenSpec specifications passed strict validation; local experiment figures remain characterization evidence only.

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
