## Why

V2 proves Redis admission can shed excess work without replacing MySQL truth, but V3 cannot safely add RocketMQ until asynchronous acceptance, duplicate delivery, publish ambiguity, delayed-close ownership, and evidence gates have explicit contracts. V2.1 closes those design and testability gaps while keeping the production ordering path synchronous and adding no live message transport.

## What Changes

- Define a versioned asynchronous order-command envelope, stable command identity, payload fingerprint, bounded lifecycle states, and a queryable result contract for later V3 transport.
- Add a transport-neutral, idempotent command-consumption seam and deterministic harness proving duplicate and concurrent delivery converge on the existing MySQL ordering result.
- Define the Redis-admission-to-publish outcome matrix: definitive pre-publish failure may release safely, while acknowledged or ambiguous publication retains or quarantines admission until durable MySQL evidence resolves it.
- Define V3's future HTTP acceptance boundary: `202 Accepted` means only that the broker acknowledged the command; created/existing-order results remain derivable only from committed MySQL state.
- Make delayed delivery an acceleration trigger for the existing idempotent MySQL expiration transaction, with the database scan retained as the authoritative recovery fallback.
- Add a pinned RocketMQ compatibility spike and a schema-validated readiness/fault matrix covering broker acknowledgement, duplicate/redelivered commands, restart, delay behavior, consumer interruption, and observability evidence.
- Preserve the existing synchronous endpoint and V2 Redis behavior; V2.1 adds no live RocketMQ producer/consumer, Transactional Outbox, CDC, automated refund execution, production availability, or production capacity claim.

## Capabilities

### New Capabilities

- `asynchronous-order-contract`: Transport-neutral command identity, envelope, acceptance/result states, idempotent consumption seam, and future V3 HTTP semantics.
- `messaging-readiness-experiments`: Reproducible RocketMQ compatibility spikes and deterministic failure evidence required before V3 implementation.

### Modified Capabilities

- `synchronous-ordering`: Preserve the current endpoint while requiring asynchronous command execution to converge on the same idempotency and MySQL business invariants.
- `redis-admission-control`: Define safe release, retention, and quarantine rules around definitive and ambiguous future message publication outcomes.
- `payment-and-expiration`: Define delayed messages as duplicate-safe triggers and retain database scanning as the recovery authority for expired orders.
- `concurrency-experiments`: Extend evidence reconciliation to command acceptance, publication, delivery, consumption, and final MySQL effects.

## Impact

- Adds internal command-contract types, lifecycle persistence/read models, an idempotent consumer application port, and deterministic adapters/tests without activating RocketMQ in the normal application path.
- Extends experiment manifests, schemas, reports, metrics, Compose/Testcontainers spike configuration, runbooks, ADRs, README, changelog, and verification status for the V2.1 boundary.
- Prepares later changes to add RocketMQ dependencies and public asynchronous endpoints without weakening the existing `purchase_claim`, idempotency-record, inventory, payment, expiration, or Redis-generation invariants.
- Requires a separate V3 OpenSpec change before any live producer, consumer, broker-dependent endpoint, or delayed-message runtime is enabled.
