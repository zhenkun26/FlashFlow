## 1. Freeze the V2.1 Baseline and Boundaries

- [x] 1.1 Record `02d224f` as the clean V2 release baseline and rerun the full Maven/Testcontainers and strict OpenSpec gates before V2.1 implementation.
- [x] 1.2 Add explicit V2.1 configuration guards that keep the public ordering runtime synchronous and reject any accidental broker-dependent mode outside the isolated spike profile.
- [x] 1.3 Add source/dependency boundary checks proving V2.1 contains no live RocketMQ producer/consumer, dispatcher, Transactional Outbox, CDC, automatic fail-open, or production reliability claim.
- [x] 1.4 Define bounded vocabulary for command lifecycle, publication outcome, delivery outcome, consumer outcome, expiration-trigger outcome, and readiness status.

## 2. Add the Versioned Command Contract

- [x] 2.1 Define a versioned order-command envelope with stable command identity, caller and SKU fields, payload fingerprint, creation time, and bounded trace correlation independent of broker client types.
- [x] 2.2 Derive command identity from the existing scoped operation/caller/idempotency identity and ensure raw user IDs and idempotency keys do not enter broker keys, metric tags, or retained reports.
- [x] 2.3 Validate required fields, size bounds, supported schema versions, equivalent replay, and conflicting identity reuse before business execution.
- [x] 2.4 Define query-safe command summaries that expose bounded lifecycle and durable result fields without presenting acceptance as order completion.
- [x] 2.5 Add serialization contract fixtures and compatibility tests for every supported envelope and result version.

## 3. Persist a Non-Outbox Command Lifecycle

- [x] 3.1 Add an additive Flyway migration for a command ledger with unique command identity, fingerprint, schema version, bounded status, result code, optional order ID, attempt counters, and timestamps.
- [x] 3.2 Add database constraints and indexes for identity lookup, status inspection, bounded recovery queries, and legal expected-state transitions.
- [x] 3.3 Implement create-or-replay, conditional claim, completion, rejection, retryable, and unresolved persistence operations without a polling dispatcher or publication payload queue.
- [x] 3.4 Verify concurrent inserts and claims for one command converge, conflicting fingerprints are rejected, and terminal results cannot be overwritten by stale attempts.
- [x] 3.5 Document explicitly why the ledger is not an Outbox and how a later V4 Outbox can reference the same command identity.

## 4. Build and Verify the Idempotent Consumer Seam

- [x] 4.1 Introduce a transport-neutral consumer port and in-process deterministic delivery adapter with no RocketMQ dependency in the normal application graph.
- [x] 4.2 Route valid commands through the existing ordering application boundary using the original scoped idempotency identity and complete the ledger only from its durable result.
- [x] 4.3 Define acknowledgement eligibility so a delivery is acknowledged only after the command result is durably recoverable or deterministically non-retryable.
- [x] 4.4 Add sequential and concurrent duplicate-delivery tests proving one command, idempotency record, effective claim, order, reservation, and reserve movement.
- [x] 4.5 Add same-identity/different-payload and unsupported-version tests proving deterministic rejection without MySQL or Redis business effects.
- [x] 4.6 Add deterministic interruption before MySQL commit and after commit-before-ack, then prove redelivery produces no partial or duplicate effect.
- [x] 4.7 Add synchronous-versus-command races proving both paths converge on one result with unchanged result precedence and bounded whole-transaction retries.

## 5. Freeze Admission-to-Publication Semantics

- [x] 5.1 Define a transport-neutral publication result model for definitely-not-published, broker-acknowledged, and ambiguous outcomes with bounded causes.
- [x] 5.2 Implement a deterministic producer seam that does not contact a broker but exercises the future acquire, publish-result, confirm/release/quarantine decision matrix.
- [x] 5.3 Verify definitive pre-publication failure releases a held token exactly once only when no command or MySQL attempt can result.
- [x] 5.4 Verify broker acknowledgement retains capacity until durable consumer outcome confirms, safely releases, or quarantines the admission.
- [x] 5.5 Verify timeout or lost acknowledgement never returns capacity, retries use the same command identity, and ambiguous state remains visible to reconciliation.
- [x] 5.6 Specify the future V3 `202 Accepted` and command-status HTTP contract with contract tests while keeping those routes disabled in V2.1 runtime.

## 6. Make Expiration Ready for Delayed Triggers

- [x] 6.1 Extract an order-specific expiration application entry point that locks and validates committed order state and `expiresAt` before invoking the existing atomic closure effects.
- [x] 6.2 Route the existing batch scanner through the same closure boundary without changing its bounded selection, recovery role, or after-commit Redis lifecycle behavior.
- [x] 6.3 Define a versioned delayed-expiration envelope containing only order identity, expected expiry, schema version, and bounded correlation data.
- [x] 6.4 Add deterministic tests for early trigger, exact/late trigger, duplicate trigger, paid-order trigger, and trigger-versus-payment commit ordering.
- [x] 6.5 Add deterministic trigger-versus-scanner races proving one closure, one stock release, one claim removal, and duplicate-safe Redis release/reconciliation.
- [x] 6.6 Verify a missing delayed trigger is still recovered by the database scanner and document the scanner as the V3 safety net.

## 7. Build the Isolated RocketMQ Compatibility Spike

- [x] 7.1 Select and pin compatible RocketMQ client, name-server, broker, and container image versions from primary documentation and record the selection rationale.
- [x] 7.2 Add a Compose/Testcontainers spike profile isolated from normal runtime, with explicit storage/flush, acknowledgement, retry, delay, resource, port, and cleanup configuration.
- [x] 7.3 Extend the experiment manifest and schema with broker/client identity, topology, acknowledgement mode, retry settings, delay mechanism, injected fault, and resolved environment inputs.
- [x] 7.4 Implement synthetic envelope publish/consume probes for broker acknowledgement, duplicate/redelivery, unsupported version, poison handling, and observed metadata.
- [x] 7.5 Implement deterministic fault seams for lost producer response, consumer interruption before/after a synthetic commit, acknowledgement loss, and broker restart.
- [x] 7.6 Measure delayed delivery behavior, early/late bounds, and duplicate delivery without using the result as an order-timer or production SLA claim.
- [x] 7.7 Retain append-only spike reports and mark unsupported or unavailable behavior `FAIL` or `BLOCKED` rather than adapting the application contract silently.

## 8. Extend Evidence, Metrics, and Reconciliation

- [x] 8.1 Add bounded metrics for command creation/replay/conflict, publication classification, delivery/redelivery, consumer claim/result/acknowledgement, trigger outcomes, and unresolved work.
- [x] 8.2 Extend evidence reports to reconcile transport attempts to stable command identities, ledger terminal/unresolved states, Redis lifecycle counts, and committed MySQL business effects.
- [x] 8.3 Reject a report whose produced, acknowledged, ambiguous, delivered, redelivered, consumed, rejected, unresolved, or committed-state totals do not reconcile.
- [x] 8.4 Add a duplicate-heavy command-harness case and controlled failure cases without presenting message attempt rate as order throughput.
- [x] 8.5 Produce a revision-bound V3 readiness report linking synchronous compatibility, Redis fault/reconciliation, command interleavings, expiration races, RocketMQ spike, and strict OpenSpec evidence.

## 9. Complete V2.1 Documentation and Release Gates

- [x] 9.1 Add ADRs for stable command identity, non-Outbox lifecycle persistence, broker-acknowledged acceptance, idempotent consumption, and scanner-backed delayed expiration.
- [x] 9.2 Add V2.1 architecture and runbook documentation covering command states, publication ambiguity, operator inspection, spike reproduction, and rollback.
- [x] 9.3 Update README, changelog, deferred roadmap, scenario matrix, and current verification status with precise V2.1/V3/V4 boundaries.
- [x] 9.4 Run the complete Maven/Testcontainers suite, explicit synchronous compatibility selection, manifest validation, strict OpenSpec validation, deterministic command/expiration fault drills, and RocketMQ compatibility matrix.
- [x] 9.5 Record every gate as `PASS`, `FAIL`, `BLOCKED`, or `NOT_RUN`; assert V3 readiness only if all required evidence belongs to the same revision and passes.
- [x] 9.6 Review all six delta specs for synchronization, archive the completed change only after all tasks pass, and request commit/push authorization separately.
