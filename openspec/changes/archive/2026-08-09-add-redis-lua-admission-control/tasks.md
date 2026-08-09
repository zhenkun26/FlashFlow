## 1. Freeze the V2 Baseline and Runtime Contract

- [x] 1.1 Retain the clean `3fd476d` correctness and stock-first experiment inputs/results as the attributable MySQL-only comparison baseline.
- [x] 1.2 Add the Redis client dependency, pin a Redis image for Docker Compose and Testcontainers, and expose explicit connection, timeout, admission-mode, held-resolution, and script-version configuration.
- [x] 1.3 Add startup validation that permits `MYSQL_ONLY` only when explicitly selected and rejects missing or unsafe Redis admission configuration without silently falling back.
- [x] 1.4 Extend local, test, and lab configuration and documentation while preserving V1 behavior when V2 is disabled.

## 2. Define Admission Domain Boundaries

- [x] 2.1 Introduce an admission port with bounded acquire, confirm, release, quarantine, and generation-state results independent of Redis client types.
- [x] 2.2 Define stable admission identity and user identity digests from the scoped ordering idempotency inputs without exposing raw identifiers in keys, logs, reports, or metric tags.
- [x] 2.3 Define versioned single-slot SKU key construction, generation metadata, lifecycle states, resolution deadlines, and bounded decision codes.
- [x] 2.4 Provide an explicit MySQL-only admission adapter for controlled baseline and rollback operation.

## 3. Implement Atomic Redis Lua State Transitions

- [x] 3.1 Implement and test generation initialization/publication scripts that expose only a complete `READY` generation and reject stale fences or schema versions.
- [x] 3.2 Implement the acquire script to recover an existing operation, enforce one active user token, bound remaining capacity, create one `HELD` admission, and return stable bounded decisions atomically.
- [x] 3.3 Implement the confirm script as an idempotent `HELD -> CONFIRMED` transition that never increases availability and cannot mutate a stale generation.
- [x] 3.4 Implement safe release for `HELD -> RELEASED` and committed unpaid closure for `CONFIRMED -> RELEASED`, returning capacity once, never exceeding initialized capacity, and never mutating a stale generation.
- [x] 3.5 Implement quarantine/not-ready transitions for ambiguous outcomes and inconsistent or missing keys without auto-returning held capacity.
- [x] 3.6 Load and execute scripts through verified digests/version metadata and classify Redis timeout, unavailable, malformed reply, version mismatch, not-ready, and stale-generation outcomes.

## 4. Integrate Admission with Synchronous Ordering

- [x] 4.1 Add a read-only durable idempotency lookup before admission so completed MySQL results replay even when Redis is unavailable, while unresolved requests still pass through the normal transactional claim.
- [x] 4.2 Orchestrate new business attempts as acquire admission, execute the unchanged stock-first MySQL transaction, then confirm, safely release, or quarantine according to the proven outcome.
- [x] 4.3 Map `NO_TOKEN`, unavailable, timeout, not-ready, and stale-generation decisions to the existing retryable 503-class contract without asserting MySQL `SOLD_OUT` or starting its ordering transaction.
- [x] 4.4 Resolve `USER_ACTIVE` against durable MySQL idempotency/claim state so a committed order is returned from MySQL and an unresolved held token remains retryable.
- [x] 4.5 Confirm committed effective orders, safely release inactive/existing/proven-rollback outcomes, and quarantine MySQL sold-out, unknown-commit, or ambiguous post-commit Redis outcomes.
- [x] 4.6 Verify concurrent same-key and same-user requests converge on one Redis admission and at most one committed MySQL effect.
- [x] 4.7 Invoke confirmed-token release only after the unpaid-closure transaction commits; quarantine a failed or ambiguous Redis release without rolling back MySQL closure.
- [x] 4.8 Verify paid tokens remain consumed and payment, expiration, post-closure reorder, claim-race, and all MySQL inventory invariants remain compatible with V2 admission.

## 5. Build Authoritative Reconciliation

- [x] 5.1 Add read models that obtain committed MySQL inventory, effective claim/order/reservation, and scoped idempotency evidence needed to resolve one SKU generation.
- [x] 5.2 Implement a bounded per-SKU maintenance fence that closes admission before snapshot and fails safely if ownership is lost.
- [x] 5.3 Classify excess and missing capacity, orphaned or stale Redis tokens, missing confirmations, stale generations, and ambiguous held admissions against MySQL evidence.
- [x] 5.4 Resolve held tokens with committed effects as consumed, release only tokens proven to have no committed or in-progress effect, and retain unresolved capacity as unavailable.
- [x] 5.5 Build and validate a replacement generation from the authoritative snapshot and publish it atomically without allowing old-generation operations to mutate it.
- [x] 5.6 Produce an append-only reconciliation report with run identity, snapshot boundary, bounded discrepancy/action counts, unresolved items, before/after evidence, and `PASS`, `FAIL`, or `BLOCKED` status.

## 6. Add Deterministic Correctness and Failure Tests

- [x] 6.1 Add Redis integration tests for concurrent excess demand, non-negative bounded capacity, one active user token, same-operation replay, and different-operation duplicate-user decisions.
- [x] 6.2 Add lifecycle tests proving confirmation, pre-order release, and post-closure confirmed release are idempotent, paid tokens stay consumed, stale generations cannot mutate current state, and held deadlines never auto-return capacity.
- [x] 6.3 Add a lost-acquire-reply test proving same-key replay recovers the original token without another decrement or MySQL effect.
- [x] 6.4 Add unavailable, timeout, restart, missing-key, partial-generation, and unknown-script tests proving normal runtime fails closed without MySQL fallback.
- [x] 6.5 Add cross-store tests for MySQL rejection after admission, MySQL commit followed by Redis confirmation failure, and unknown MySQL outcomes without unsafe compensation.
- [x] 6.6 Add reconciliation fixtures for excess, missing, orphaned, stale, unconfirmed, and unresolved admission state and verify MySQL remains unchanged.
- [x] 6.7 Run the complete pre-existing 37-test MySQL suite with Redis admission disabled to prove backward compatibility.

## 7. Extend Metrics and Experiment Evidence

- [x] 7.1 Add bounded metrics for every admission decision, MySQL attempt avoided/started, lifecycle outcome, quarantine cause, reconciliation discrepancy/action, and unexpected failure.
- [x] 7.2 Extend the experiment manifest and validator with admission mode, Redis runtime/script identity, generation, resolution deadline, and injected-failure inputs while preserving one-factor comparison checks.
- [x] 7.3 Extend orchestration and reports to capture Redis configuration, final token accounting, key/memory evidence, lifecycle reconciliation, and existing committed MySQL invariant results.
- [x] 7.4 Add a canonical Redis-admission case paired with the clean stock-first MySQL-only baseline and reject reports whose request, admission, lifecycle, or committed-state counts do not reconcile.
- [x] 7.5 Run Redis outage, ambiguous reply, restart/state-loss, duplicate lifecycle, and drift/rebuild drills and retain their explicit evidence states.
- [x] 7.6 Run the controlled local V2 comparison only after correctness gates pass and document results as local characterization rather than production capacity.

## 8. Complete Documentation and Release Gates

- [x] 8.1 Document the V2 request/token lifecycle, generation model, failure matrix, reconciliation procedure, configuration, and explicit MySQL-only rollback runbook.
- [x] 8.2 Update the architecture decisions, deferred roadmap, README, changelog, and current verification status with precise Redis-as-admission-layer boundaries.
- [x] 8.3 Verify no RocketMQ, Outbox, CDC, distributed lock, automatic fail-open path, production-capacity claim, or application-code-owned durable inventory entered V2.
- [x] 8.4 Run the complete Maven/Testcontainers suite, strict OpenSpec validation, deterministic fault drills, and canonical experiment pair; record each gate as `PASS`, `FAIL`, `BLOCKED`, or `NOT_RUN`.
- [x] 8.5 Review delta-to-main spec synchronization readiness and capture any remaining deferred work before requesting archive or publication authorization separately.
