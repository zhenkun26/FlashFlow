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
