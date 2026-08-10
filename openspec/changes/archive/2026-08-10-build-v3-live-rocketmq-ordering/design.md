## Context

See `proposal.md` for motivation. V2.1 already supplies a versioned privacy-preserving command identity, a non-Outbox command ledger, a transport-neutral idempotent consumer, publication-outcome decisions, an order-specific expiration entry point, a scanner recovery path, and a pinned RocketMQ 5.3.4 compatibility topology. Normal runtime still loads no broker client and exposes no asynchronous route.

The source-of-truth boundaries do not change: Redis may admit work, RocketMQ may transport it, and only committed MySQL state may establish an order, inventory movement, payment, or expiration result. The design must also preserve the deliberate learning boundary between V3 direct publication and V4 Transactional Outbox/CDC reliability.

## Goals / Non-Goals

**Goals:**

- Activate the existing command and expiration contracts through a real, explicitly selected RocketMQ runtime.
- Make every HTTP, producer, consumer, retry, dead-letter, Redis, and MySQL outcome attributable to one stable command identity.
- Preserve synchronous compatibility and provide configuration-only rollback.
- Retain revision-bound evidence for live application traffic and deterministic failure interleavings.

**Non-Goals:**

- Guarantee eventual publication across a crash between local preparation and broker acknowledgement.
- Add an Outbox table, CDC, polling dispatcher, publication lease, or broker-confirmation recovery worker.
- Disable the expiration scanner or make RocketMQ authoritative for business state.
- Claim production availability, delay SLA, persistence, failover, throughput, or capacity.
- Add microservices, automated refunds, or a real payment provider.

## Decisions

### 1. Add an explicit live mode; keep disabled mode broker-free

Extend the messaging mode with a live V3 value. Only this mode creates RocketMQ producers, order consumers, delayed-expiration consumers, topics, and groups and exposes `/api/v2/orders`. Startup validates endpoints, pinned client/broker compatibility inputs, distinct topic/group identities, acknowledgement mode, retry bounds, delay settings, and required Redis admission configuration. `DISABLED` continues to load no RocketMQ client; the isolated spike remains a verification topology rather than a normal traffic mode.

This keeps rollback observable and prevents an accidental broker dependency from changing `/api/v1/orders`. Alternative: always load clients but conditionally skip sends. Rejected because startup, health, and connection behavior would still make synchronous mode broker-dependent.

### 2. Prepare durable identity, then publish directly and acknowledge honestly

The asynchronous controller validates and fingerprints the request, resolves completed idempotent replay, acquires Redis admission, and creates or reuses the bounded command-ledger identity before one bounded direct publish. The broker key is the stable command ID; a transport-attempt ID may vary and is never used for business deduplication.

Only a trustworthy broker acknowledgement transitions the command to `ACCEPTED` and permits `202 Accepted`. A definitive pre-acceptance failure records a retryable result and safely releases admission when no downstream attempt can result. Timeout, lost acknowledgement, client interruption, or any uncertain outcome records `UNRESOLVED`, retains or quarantines admission, returns a bounded retryable response, and requires retry with the same command identity. A prepared row left by process failure is visible but is not treated as proof of publication.

This intentionally does not guarantee eventual publication. Alternative: return `202` after writing the command row. Rejected because there is no dispatcher. Alternative: add an Outbox in V3. Rejected to preserve the planned direct-publication versus Outbox reliability comparison; V4 can reference the same command ID without changing consumer semantics.

### 3. Map status from durable ledger and MySQL evidence

The status resource is caller-scoped and returns a bounded view over `PREPARED`, `ACCEPTED`, `PROCESSING`, `COMPLETED`, `REJECTED`, `RETRYABLE`, or `UNRESOLVED`. It includes an order ID only when the ledger or source-of-truth replay proves a committed order. Transport diagnostics remain bounded and do not expose raw user IDs, idempotency keys, exception text, broker payloads, or unbounded cardinality.

Alternative: return the eventual business result from the original POST connection. Rejected because it collapses acceptance and completion and recreates synchronous timeout coupling.

### 4. Use at-least-once consumption with recoverable-result acknowledgement

The order consumer validates schema version and fingerprint, conditionally claims the ledger row, and invokes the existing transport-neutral executor. It acknowledges only after a completed or rejected result is durably recoverable, or after deterministic envelope validation produces a terminal non-business rejection. A retryable technical result does not become a false business rejection. Concurrent and redelivered messages either observe a current owner or replay the stable durable result.

Broker-managed delivery attempts are strictly bounded and observable. Transient failures remain eligible for redelivery; exhausted attempts and deterministic poison envelopes go to a dedicated dead-letter topic. Dead-letter evidence contains bounded reason, command ID, schema version, original topic, and attempt metadata, not sensitive request identity. Reprocessing is a manual laboratory operation in V3 and must reuse the original command identity.

Alternative: acknowledge before database handling and retry in application memory. Rejected because a crash could lose accepted work and memory retries are not attributable after restart. Alternative: retry forever. Rejected because poison traffic would block useful work and hide unresolved state.

### 5. Apply Redis lifecycle only from attributable transport or MySQL evidence

The producer uses the existing publication decision matrix: definitive no-publication can release once, acknowledgement retains capacity, and ambiguity quarantines. The consumer confirms an effective order, safely releases only a proven non-effective terminal outcome, and quarantines uncertain or drifted outcomes. Dead-lettering alone is not proof that no earlier delivery committed, so it does not automatically return capacity. Existing fenced reconciliation resolves held and quarantined identities from committed MySQL facts.

Alternative: release every non-202 or dead-lettered command. Rejected because a lost response or post-commit failure could manufacture Redis capacity beyond MySQL stock.

### 6. Publish delayed expiration after commit; keep scanning authoritative for recovery

After a newly created pending-payment order commits, an `afterCommit` action publishes a versioned delayed envelope containing only order ID, committed `expiresAt`, schema version, and bounded correlation. Publication failure cannot roll back the order and is recorded for inspection. The consumer calls the existing locked order-specific expiration boundary. It acknowledges `CLOSED`, terminal-state skip, or not-found outcomes; an early trigger remains eligible for bounded redelivery while the scanner guarantees eventual eligibility processing.

The scanner remains enabled in live mode with an explicit scan delay and batch size. Evidence measures trigger timing and scanner recovery locally but does not call either an SLA.

Alternative: publish before order commit. Rejected because the trigger could observe an order that rolls back. Alternative: disable scanning. Rejected because V3 direct publication cannot guarantee trigger delivery.

### 7. Separate real-broker integration gates from deterministic seams

Testcontainers or disposable Compose runs the pinned broker for application-level tests. Deterministic seams still force precise lost-ack and crash interleavings, while at least one retained run must traverse the actual HTTP controller, real producer, Broker, real consumer, MySQL, Redis, status resource, delayed consumer, and scanner. Reports reconcile stable identities rather than only aggregate counts and retain `PASS`, `FAIL`, `BLOCKED`, and `NOT_RUN` separately.

The required gate order is: source/configuration guard tests; full Maven/Testcontainers correctness suite; synchronous compatibility; live happy path; producer and consumer fault drills; retry/DLQ; delayed-trigger/scanner recovery; Redis/MySQL reconciliation; controlled local characterization; strict OpenSpec and clean revision checks.

Alternative: accept the existing synthetic RocketMQ round trip as V3 evidence. Rejected because it does not execute the application producer, consumer, database, Redis, or HTTP contracts.

## Risks / Trade-offs

- [Direct publication can leave a prepared command unpublished after process failure] → Expose `PREPARED`/`UNRESOLVED`, never return `202` without Broker acknowledgement, require same-identity retry, and reserve eventual publication for V4 Outbox.
- [A lost producer acknowledgement can retain admission indefinitely] → Quarantine by stable identity, expose age/count metrics, and resolve through existing authoritative reconciliation without blindly releasing capacity.
- [Consumer retry and DLQ settings may differ across client and Broker versions] → Pin and report both sides, validate resolved configuration, and retain a real-broker retry/DLQ drill.
- [Order and delayed-trigger publication are not atomic] → Publish only after commit, retain scanner recovery, and report trigger publication failures separately from order success.
- [Both synchronous and asynchronous routes increase race combinations] → Reuse one scoped identity and ordering boundary and keep deterministic sync-versus-command tests in the release gate.
- [Live Broker tests may be slow or infrastructure-dependent] → Mark unavailable infrastructure `BLOCKED`, keep deterministic unit seams fast, and require retained live evidence before the V3 release claim.

## Migration Plan

1. Add the RocketMQ dependency, live-mode configuration, and startup guards while `DISABLED` remains the default.
2. Provision disposable topics and groups and verify producer, consumer, retry, dead-letter, and delay behavior without public traffic.
3. Enable the asynchronous controller and status resource only in live mode; run deterministic and real-broker end-to-end gates.
4. Run the controlled local V3 evidence matrix, reconcile Redis and committed MySQL state, and bind results to a clean revision.
5. Enable V3 only in the local laboratory after every required gate passes. Roll back by selecting `DISABLED` and stopping the V3 Broker profile; `/api/v1/orders` and durable MySQL data require no rollback migration.
