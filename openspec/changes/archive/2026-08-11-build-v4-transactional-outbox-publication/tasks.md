## 1. Establish V4 Boundaries and Baseline

- [x] 1.1 Add focused characterization tests for current V3 direct `SEND_OK`, definitive failure, ambiguous publication, same-identity replay, consumer idempotency, and `DISABLED` behavior before changing runtime modes.
- [x] 1.2 Add source and dependency boundary checks that permit a polling Outbox but reject CDC/Debezium, Kafka Connect, automatic DLQ replay, Redis-owned publication authority, and production HA/capacity claims.
- [x] 1.3 Map every V4 delta scenario to a deterministic, Testcontainers, real RocketMQ, configuration, reconciliation, or retained-evidence gate in the scenario matrix.

## 2. Add the Outbox Schema and Persistence Contract

- [x] 2.1 Add a Flyway migration for `order_command_outbox` with immutable command/envelope/routing fields, one-to-one command identity, lifecycle constraints, attempt and scheduling fields, lease ownership, bounded result evidence, timestamps, and foreign key integrity.
- [x] 2.2 Add eligibility and correlation indexes that support ordered ready/retry/expired-lease polling without scanning acknowledged or terminal transport history.
- [x] 2.3 Implement Outbox row, state, mapper, and repository operations for insert-or-verify, caller-safe lookup, bounded claim, acknowledged, retryable, invalid, exhausted, and expired-claim transitions.
- [x] 2.4 Require expected state and current lease token on claimed-result mutations so a stale dispatcher cannot overwrite a successor.
- [x] 2.5 Verify migration constraints, immutable replay validation, one-Outbox-per-command convergence, indexes, and illegal state-transition rejection against MySQL 8.4.6 Testcontainers.
- [x] 2.6 Verify the migration does not backfill or make historical V3 `PREPARED` and `UNRESOLVED` ledger rows dispatchable.

## 3. Implement Atomic Durable Acceptance

- [x] 3.1 Add a transactional application boundary that creates or validates the stable command and canonical versioned envelope, inserts or verifies the Outbox row, and marks the command accepted in one MySQL commit.
- [x] 3.2 Keep Redis acquisition before durable acceptance and preserve the existing confirm, release, retained, and quarantined decisions when the MySQL commit succeeds, fails, or is ambiguous.
- [x] 3.3 Implement same-identity replay that verifies command fingerprint, schema, canonical payload fingerprint, and routing metadata before returning the existing durable acceptance.
- [x] 3.4 Return V4 `202 Accepted` only from a proven command-plus-Outbox commit, return no created-order claim, and preserve caller-scoped status and durable completion lookup.
- [x] 3.5 Add transaction fault injection proving command-insert failure, Outbox-insert failure, ledger-transition failure, rollback, and uncertain client response expose no partial accepted pair.
- [x] 3.6 Add lost-response and process-stop-after-commit tests proving a caller retry creates no duplicate command, Outbox row, admission, or business effect.
- [x] 3.7 Verify synchronous ordering and synchronous/asynchronous races still converge on the existing scoped idempotency and purchase-claim invariants.

## 4. Make Messaging Modes Explicit

- [x] 4.1 Replace configuration value `LIVE` with `DIRECT`, add `OUTBOX`, retain `DISABLED` as default, and reject legacy or unknown values with a bounded migration error.
- [x] 4.2 Refactor conditional wiring so `DIRECT` alone performs inline publication, `OUTBOX` alone performs atomic acceptance and starts dispatch, and `DISABLED` creates no RocketMQ client or dispatcher.
- [x] 4.3 Validate Outbox batch, polling, lease, producer-timeout margin, attempt, backoff, routing, group, and acknowledgement settings before serving traffic.
- [x] 4.4 Update configuration tests to reject incomplete, contradictory, unsafe, shared-topic/group, and dual-publication configurations.
- [x] 4.5 Verify `DIRECT` reproduces V3 HTTP and publication behavior and never inserts or claims Outbox work.
- [x] 4.6 Verify `/api/v1/orders` remains available and compatible under `DISABLED`, `DIRECT`, and `OUTBOX`.

## 5. Implement Lease-Based Polling Dispatch

- [x] 5.1 Implement bounded eligibility selection for `READY`, due `RETRYABLE`, and expired `CLAIMED` records using deterministic order and `FOR UPDATE SKIP LOCKED` inside a short transaction.
- [x] 5.2 Assign a unique lease token, bounded owner identifier, expiry, and incremented durable attempt to each claimed row before committing the claim transaction.
- [x] 5.3 Deserialize and revalidate the immutable envelope after claiming, then publish through the existing RocketMQ publisher outside the database transaction.
- [x] 5.4 Record trustworthy `SEND_OK` as `ACKNOWLEDGED` only when the claim token is current and prevent acknowledged rows from normal redispatch.
- [x] 5.5 Map definitive and ambiguous publication failures within budget to bounded exponential retry scheduling without claiming Broker non-delivery.
- [x] 5.6 Map deterministic envelope/schema/fingerprint/routing violations to `INVALID` without publication and map attempt exhaustion to `EXHAUSTED` without further automatic claims.
- [x] 5.7 Add a scheduled dispatcher loop with bounded batches, idle behavior, safe exception containment, lifecycle startup, and graceful shutdown that stops new claims before interrupting in-flight work.
- [x] 5.8 Add deterministic clock-based tests for eligibility ordering, retry due time, attempt count, active-lease exclusion, expiry takeover, stale-owner rejection, invalid records, exhaustion, and empty polls.
- [x] 5.9 Add concurrent MySQL integration tests proving multiple dispatcher instances do not hold the same active claim and expired ownership is recoverable.

## 6. Prove At-Least-Once Recovery Through RocketMQ

- [x] 6.1 Extend real RocketMQ fixtures to start `OUTBOX` mode and inspect command, Outbox, Broker, consumer, Redis, and MySQL state by stable identity.
- [x] 6.2 Prove normal durable acceptance dispatches, receives Broker acknowledgement, consumes, commits one business result, and retains attributable Outbox evidence.
- [x] 6.3 Prove a Broker outage spanning HTTP acceptance leaves durable backlog and automatically publishes it after recovery without a caller retry.
- [x] 6.4 Prove a process stop after Outbox claim but before send is recovered after lease expiry by another dispatcher.
- [x] 6.5 Prove lost producer acknowledgement and a stop after Broker acceptance but before `ACKNOWLEDGED` may republish yet produce one terminal command and one MySQL business effect.
- [x] 6.6 Prove concurrent dispatchers plus consumer acknowledgement loss and redelivery preserve command, order, claim, reservation, movement, and admission invariants.
- [x] 6.7 Prove invalid stored envelopes and bounded retry exhaustion stop automatic dispatch, remain inspectable, and do not release Redis admission or claim business completion.
- [x] 6.8 Prove a bounded accumulated backlog drains after Broker recovery and report backlog age, attempts, duplicates, completion latency, and final invariants without a production-capacity claim.

## 7. Extend Admission Reconciliation

- [x] 7.1 Extend authoritative reconciliation facts with the one-to-one Outbox state and bounded publication disposition for each relevant command identity.
- [x] 7.2 Classify ready, actively claimed, retryable, and acknowledged-but-nonterminal commands as durable in-flight work that retains admission capacity.
- [x] 7.3 Classify invalid and exhausted Outbox work as operator-required without inferring safe release from transport state.
- [x] 7.4 Block convergence for missing, unreadable, duplicate, or contradictory accepted-command/Outbox evidence while leaving MySQL business state unchanged.
- [x] 7.5 Permit release only when the existing fenced authoritative checks independently prove no accepted Outbox, possible publication, in-progress transaction, or committed effect can consume the token.
- [x] 7.6 Add reconciliation fixtures for every Outbox lifecycle state, stale claim, missing pair, contradictory pair, terminal command, and proven-no-effect release.

## 8. Add Operations, Privacy, and Retention Evidence

- [x] 8.1 Add low-cardinality metrics for mode, ready backlog, oldest eligible age, claims, lease takeovers, attempts, acknowledgements, retries, invalid items, exhaustion, and unresolved accepted work.
- [x] 8.2 Add bounded structured logs and append-only report fields correlated by privacy-preserving command and Outbox identity while excluding raw caller IDs, idempotency keys, payload bodies, Broker bodies, exception text, and high-cardinality metric tags.
- [x] 8.3 Extend experiment evidence validation so every durable acceptance reconciles to one Outbox row, a bounded publication disposition, final command state, Redis lifecycle, and committed MySQL outcome.
- [x] 8.4 Reject `PASS` when accepted work is missing Outbox evidence, active beyond its bound, contradictory, or otherwise unresolved.
- [x] 8.5 Implement disabled-by-default terminal cleanup eligibility, if retained in scope, and prove only acknowledged rows linked to terminal commands beyond retention can be selected; do not enable deletion in V4 verification runs.
- [x] 8.6 Add operator inspection commands and safe runbook procedures for pausing dispatch, measuring backlog, resuming expired work, inspecting invalid/exhausted records, and preserving rows during rollback without automatic replay or capacity release.

## 9. Build the V3/V4 Comparison and Final Gates

- [x] 9.1 Add a controlled `DIRECT`/`OUTBOX` experiment using equivalent dataset, identities, admission settings, RocketMQ topology, concurrency, observation bounds, and declared mode-specific inputs.
- [x] 9.2 Separate HTTP acceptance latency, Broker acknowledgement time, completion latency, duplicate publication, recovery time, backlog drain, and business rejection in the comparison report.
- [x] 9.3 Demonstrate the declared outage contrast: `DIRECT` does not accept without Broker acknowledgement while `OUTBOX` durably accepts and automatically publishes after recovery.
- [x] 9.4 Run the complete Maven/Testcontainers suite, explicit synchronous compatibility suite, mode/configuration tests, deterministic Outbox fault matrix, admission reconciliation matrix, and real RocketMQ recovery matrix.
- [x] 9.5 Validate Compose resolution, Flyway migration, script syntax, source/dependency boundaries, privacy scans, OpenSpec strict validation, and `git diff --check`.
- [x] 9.6 Record machine, container limits, dataset, revision, dirty-worktree state, duration, concurrency, fault schedule, counts, latency dimensions, backlog, retries, duplicates, final balances, and invariant results in a revision-bound V4 local report.
- [x] 9.7 Rerun every required gate against one clean attributable implementation revision and keep the release `FAIL`, `BLOCKED`, or `NOT_RUN` when any identity or gate cannot reconcile.

## 10. Document and Prepare Release

- [x] 10.1 Add ADRs for durable Outbox acceptance, separate ledger/Outbox state, polling leases, at-least-once recovery, conservative exhaustion, and explicit runtime modes.
- [x] 10.2 Add V4 architecture and runbook documentation covering transaction boundaries, state machine, configuration migration, failure recovery, backlog operations, privacy, retention, and configuration-only rollback.
- [x] 10.3 Update README, changelog, deferred roadmap, scenario matrix, verification status, and V3 documentation with the exact `DIRECT`/`OUTBOX`/`DISABLED` semantics and V4 evidence boundary.
- [x] 10.4 State explicitly that V4 does not provide exactly-once publication, CDC, automated replay, production HA, capacity, persistence, or latency-SLA evidence.
- [x] 10.5 Review completed tasks and delta specs against implementation and retained evidence, synchronize the five deltas to main specs only after authorization, and archive the change only after every required gate passes.
- [x] 10.6 Keep commit, push, publication, spec synchronization, and archival as separately authorized release actions after a clean revision-bound review.
