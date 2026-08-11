# Architecture decisions

## ADR-001: Modular monolith

One Spring Boot deployment uses package-by-capability boundaries. Local transactions and lock behavior are the subject of V1, so network service boundaries would add failure modes before providing value. Revisit only when a later capability has an independent scaling, ownership, or deployment need.

## ADR-002: MySQL is the business source of truth

Order acceptance and inventory transitions depend on committed InnoDB state. Redis and MQ may later admit or transport work but cannot override MySQL. PostgreSQL and database portability are intentionally excluded because lock semantics are part of the experiment.

## ADR-003: Purchase claim enforces active uniqueness

`purchase_claim(activity_sku_id, user_id)` is the database mutex for one effective order. It is inserted and removed in the same transactions as order/inventory effects. A permanent order uniqueness key would prevent valid retry after unpaid closure; a status-dependent unique order index is not directly expressible as required in MySQL.

## ADR-004: Conditional atomic update is the normal strategy

The searched update has the smallest correct reservation window for one hot stock row. Pessimistic and optimistic alternatives remain experiments. Unsafe read-then-write is lab-only. Redis locks and database advisory locks are not needed for this invariant.

## ADR-005: Late payment becomes manual compensation

Provider success is recorded truthfully even after unpaid closure. The order is not revived because inventory may have been reassigned. V1 creates a unique open refund-required case and metric; automated refund execution is deferred to a later change.

## ADR-006: Redis is a fail-closed admission layer, not inventory truth

V2 uses one atomic Lua state machine per SKU generation to bound work before MySQL. Redis cannot produce a durable business success, override `purchase_claim`, or authorize stock beyond committed MySQL state. Missing, unavailable, timed-out, partial, or unknown-version Redis state returns the existing retryable 503-class result; there is no automatic MySQL fail-open.

## ADR-007: Cross-store uncertainty is quarantined and reconciled

The application confirms or releases a token only when the MySQL outcome proves that transition safe. Unknown commit state, post-commit Redis ambiguity, and unexplained held tokens retain capacity. Reconciliation closes admission under a bounded lease, snapshots MySQL, builds a new generation, and publishes it atomically only when the result is unambiguous.

## ADR-008: One stable command identity crosses retries and transports

V2.1 derives a privacy-preserving command ID from the existing scoped admission identity and binds it to a payload fingerprint. Producer retry, delivery retry, synchronous-versus-command races, ledger lookup, and a future Outbox all reuse that ID. Raw user IDs and idempotency keys are excluded from broker keys, metric tags, and retained reports.

## ADR-009: The command ledger is not a Transactional Outbox

The V2.1 ledger records command identity, validation, claim, attempt, and durable business result. It does not store a publication payload, expose a publish-ready queue, run a polling dispatcher, or claim atomic MySQL-to-broker publication. A V4 Outbox may reference the same command ID and independently own event payload, publication lease, attempt, and broker-confirmation state.

## ADR-010: Broker acknowledgement means accepted, not ordered

The proposed V3 HTTP boundary returns `202 Accepted` only after broker acknowledgement and exposes a separate command-status resource. A timeout or lost acknowledgement is ambiguous: capacity remains withheld, the same command ID is retried, and reconciliation must resolve the result. V2.1 defines and tests this contract but does not enable the route.

## ADR-011: Consumers acknowledge only recoverable outcomes

A delivery is acknowledged only after its terminal result is durably recoverable from MySQL, or after deterministic validation proves it non-retryable. The consumer claims by stable command ID and routes valid work through the existing ordering boundary, so duplicate delivery and a crash after commit but before acknowledgement converge on one business effect.

## ADR-012: Delayed delivery is an accelerator; the scanner remains the safety net

Both a delayed trigger and the bounded database scanner call the same order-specific expiration boundary. That boundary locks and rechecks committed order state and `expiresAt` before closing. Missing, early, duplicate, or racing triggers cannot bypass those checks; the scanner remains enabled in V3 to recover a missing message.

## ADR-013: Live messaging is explicit and rollback is configuration-only

This V3 decision originally used `FLASHFLOW_MESSAGING_MODE=LIVE`; V4 supersedes that value with `DIRECT`. `DIRECT` creates RocketMQ clients and exposes the inline-publication asynchronous route. `DISABLED` creates no Broker connection and preserves `/api/v1/orders`. Startup rejects the legacy `LIVE` value and incomplete, shared-topic, shared-group, unpinned, or contradictory active configuration.

## ADR-014: V3 uses bounded direct publication and preserves the Outbox comparison

The producer writes or reuses durable command identity, acquires admission, then publishes directly. Only Broker `SEND_OK` permits `202`; definitive no-publication can release admission, while timeout or lost acknowledgement becomes `UNRESOLVED` and quarantines capacity. A prepared row is not a publish queue, so process-crash eventual delivery remains a V4 concern.

## ADR-015: Retry exhaustion is visible but not business truth

Transient delivery failures receive a bounded Broker retry budget. Poison, unsupported, or exhausted messages are copied to a dedicated dead-letter topic with command ID, schema, source, attempt, and bounded reason evidence. DLQ disposition alone cannot prove whether MySQL previously committed and therefore cannot authorize automatic capacity release.

## ADR-016: V4 accepts an atomic command and Outbox commit

In `OUTBOX` mode, `202 Accepted` means one stable command and one immutable publish-ready envelope committed in the same MySQL transaction. It no longer requires inline Broker acknowledgement and never claims order completion. The command ledger and Outbox stay separate because business-result convergence and publication ownership have different state machines and retention.

## ADR-017: Polling leases provide recoverable at-least-once publication

V4 uses bounded MySQL polling with `FOR UPDATE SKIP LOCKED`, a unique per-claim lease token, expiration, and takeover. Broker I/O occurs outside the claim transaction. A lost acknowledgement or crash after send may republish, so the stable identity and idempotent consumer—not the dispatcher lease—prevent duplicate business effects.

## ADR-018: Runtime modes make acceptance semantics explicit

`DISABLED` is broker-free, `DIRECT` preserves the V3 inline `SEND_OK` control, and `OUTBOX` selects durable local acceptance plus recovery dispatch. The former `LIVE` value is rejected rather than silently aliased so configuration and retained evidence cannot confuse the two acceptance contracts.

## ADR-019: Outbox exhaustion is conservative transport evidence

`INVALID` and `EXHAUSTED` stop automatic dispatch and retain bounded evidence. Neither proves that no earlier delivery committed and neither releases Redis admission automatically. CDC/Debezium and automatic replay require later independent changes.
