## Context

See `proposal.md` for motivation. V2 currently returns a business result synchronously after Redis admission and a stock-first MySQL transaction. Durable idempotency and `purchase_claim` already converge duplicate HTTP execution; Redis holds volatile admission state and quarantines cross-store ambiguity. Expiration is a database scan that locks eligible orders and applies closure atomically. No broker client, command ledger, Inbox, Outbox, or delayed-message runtime exists.

V2.1 must make V3 implementable without silently claiming reliable delivery that the phase does not yet provide. The design therefore separates a transport-neutral correctness core from a disposable RocketMQ compatibility spike and keeps every live runtime path broker-free.

## Goals / Non-Goals

**Goals:**

- Freeze command identity, envelope versioning, lifecycle, query semantics, and failure classifications before selecting a live transport adapter.
- Prove an at-least-once consumer can reuse current MySQL idempotency and ordering invariants under duplicate and crash interleavings.
- Decide admission behavior for every producer outcome without increasing Redis capacity under uncertainty.
- Make delayed delivery compatible with payment races and the existing expiration recovery scan.
- Produce a revision-bound go/no-go report for a later V3 proposal.

**Non-Goals:**

- Enable a public asynchronous endpoint, RocketMQ producer/consumer, or broker dependency in normal runtime.
- Guarantee eventual publication across process failure; Transactional Outbox remains a later reliability phase.
- Add automatic retry/DLQ operations, CDC, automated refunds, microservices, or production HA/capacity claims.
- Replace the synchronous endpoint, MySQL ordering transaction, Redis reconciliation, or expiration scanner.

## Decisions

### 1. Use one stable identity across HTTP retry, Redis admission, command, and MySQL idempotency

The future producer derives `commandId` from the same scoped operation/caller/idempotency identity already used by Redis and MySQL, while the envelope carries a request fingerprint and schema version. A separate random message identifier may identify one transport attempt, but never becomes the business deduplication key.

This lets a retry after an ambiguous publish resend the same business command and lets the consumer converge through the existing durable idempotency record. Raw idempotency keys and user identities remain excluded from broker keys, metric tags, and retained reports; stable digests or bounded correlation identifiers are used instead.

Alternative: generate a new UUID for every publish attempt. Rejected because an ambiguous retry would become a second business identity and weaken both admission replay and consumer deduplication.

### 2. Persist a bounded command lifecycle, but do not build a transport Outbox

V2.1 adds a MySQL command record/read model with a unique command identity, fingerprint, envelope version, and bounded states such as `PREPARED`, `ACCEPTED`, `PROCESSING`, `COMPLETED`, `REJECTED`, `RETRYABLE`, and `UNRESOLVED`. Result code and order identity are populated only from committed MySQL evidence. Transitions use expected-state conditions and retain attempt timestamps/counts without storing unbounded error text.

The record is a query and consumer-convergence ledger, not an Outbox: V2.1 does not poll it to publish messages and does not claim atomic database-and-broker commit. The future V3 producer may create or update it around direct broker publication, with the limitation recorded explicitly; V4 can add an Outbox without changing command identity or consumer semantics.

Alternative: defer all persistence until V3. Rejected because API status and duplicate-consumer behavior would then be designed under transport pressure. Alternative: implement Outbox now. Rejected because it would collapse the intended V3/V4 learning boundary.

### 3. Define acceptance by broker acknowledgement, never by Redis or local enqueue

The future asynchronous endpoint may return `202 Accepted` only after the configured broker acknowledgement proves acceptance under the pinned topology. The response contains `commandId` and a status URI. It does not contain `orderId` unless a later query finds committed MySQL evidence.

Definitive pre-publish failure returns a retryable response and may release the held Redis token. Broker acknowledgement retains the token for consumption. Timeout or lost acknowledgement is `AMBIGUOUS`: do not claim `202`, do not return capacity, and retry with the same command identity. This phase deliberately accepts that a client which never retries after a pre-ack process crash may leave work unresolved; reconciliation exposes it rather than pretending eventual delivery.

Alternative: return `202` after writing a local command row. Rejected because no V2.1 dispatcher guarantees the message will reach RocketMQ. Alternative: fail open to synchronous MySQL. Rejected because it bypasses the declared asynchronous admission boundary.

### 4. Reuse the ordering application boundary as the idempotent consumer core

A transport-neutral consumer port accepts the versioned envelope, claims the command record conditionally, and invokes the existing ordering application using the original scoped identity. It completes the command record from the durable result and acknowledges only after that result is recoverable. A pre-commit interruption leaves no partial order effect; a post-commit acknowledgement loss replays the durable idempotency result.

V2.1 verifies this with an in-process deterministic adapter, including sequential duplicates, concurrent duplicates, fingerprint conflict, unsupported version, crash-before-commit, and crash-after-commit-before-ack. It does not need a broker to prove business idempotency.

Alternative: introduce a new MQ-specific ordering transaction. Rejected because it would duplicate correctness logic and allow synchronous and asynchronous paths to drift.

### 5. Keep MySQL transition ownership; let delayed messages and scanning compete safely

The existing conditional, locked expiration transaction remains the only authority that closes an order and releases inventory. V3 delayed messages will call an order-specific expiration entry point that checks committed `expiresAt` and expected state. The existing batch scanner remains enabled as a recovery safety net. Duplicate, early, paid-order, and scanner-versus-message attempts therefore converge on one transition.

The delayed envelope carries order identity, expected expiry, schema version, and correlation only. It never carries authority to close or a stock delta. Redis confirmed-token release remains after MySQL commit; ambiguous release continues to be reconciliation work.

Alternative: disable the scanner and make RocketMQ the sole owner. Rejected because message loss would strand reservations before Outbox/recovery guarantees exist. Alternative: allow the message to apply stock deltas directly. Rejected because it bypasses MySQL state validation.

### 6. Isolate RocketMQ as a pinned compatibility and failure spike

Compose/Testcontainers support a disposable, explicitly selected spike profile. The manifest records broker and name-server images, client version, storage/flush settings, acknowledgement mode, retry settings, delay mechanism and resolution, resource limits, and observed broker metadata. The spike uses synthetic versioned envelopes and does not serve application traffic.

Required drills cover publish acknowledgement, lost producer response, duplicate/redelivery, consumer interruption around a synthetic commit seam, acknowledgement loss, broker restart, delay timing/duplicates, unsupported envelope, and poison handling. Evidence is append-only and uses `PASS`, `FAIL`, `BLOCKED`, and `NOT_RUN` distinctly.

Alternative: add the RocketMQ dependency directly to the application and learn through V3 implementation. Rejected because topology or client limitations would then force contract changes after production code exists.

### 7. Make V3 readiness a strict, revision-bound gate

One readiness report links the complete Maven/Testcontainers suite, explicit synchronous compatibility selection, Redis admission and reconciliation failures, command-harness interleavings, expiration trigger races, strict OpenSpec validation, manifest validation, and the RocketMQ spike/fault matrix for the same Git revision. It reconciles transport attempts separately from stable commands and committed orders.

V3 is ready only when every required gate is `PASS`. Local publish rate, consumption rate, and delay observations are characterization data, not production reliability or capacity evidence.

## Risks / Trade-offs

- [The command ledger may be mistaken for an Outbox] → Name the boundary in schema, ADRs, runbooks, and reports; provide no dispatcher in V2.1.
- [Direct publication in V3 can still lose work before acknowledgement] → Do not claim eventual delivery; use stable retry identity, retain ambiguous admission, expose unresolved states, and reserve Outbox for V4.
- [Keeping both delayed triggers and scanning adds duplicate work] → Route both through one expected-state MySQL transition and measure skipped duplicate triggers separately.
- [A transport spike can pass while production topology differs] → Pin and report every local input and prohibit extrapolation to HA or capacity.
- [Command records can accumulate] → Define bounded fields and indexes now; defer retention policy until V3 traffic characteristics are measured.
- [Envelope evolution can break replay] → Reject unsupported versions before effects and retain contract fixtures for every supported version.

## Migration Plan

1. Add the command lifecycle schema and transport-neutral contract behind internal-only interfaces; do not change existing HTTP routing.
2. Add the idempotent consumer harness and prove duplicate/crash convergence against disposable MySQL and Redis.
3. Add an order-specific expiration trigger and prove races with payment, repeated triggers, and the existing scanner.
4. Add the isolated pinned RocketMQ spike profile, manifest fields, deterministic failure seams, and append-only evidence.
5. Run the complete revision-bound V2.1 gate, update documentation, and archive only after every required status is explicit.

Rollback removes the unused internal command/spike configuration and its additive schema objects; the synchronous V2 path requires no data rewrite. Any retained command evidence is non-authoritative and can remain inert.
