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
