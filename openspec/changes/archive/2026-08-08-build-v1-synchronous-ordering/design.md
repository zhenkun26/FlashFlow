## Context

The repository currently contains only OpenSpec scaffolding. See `proposal.md` for motivation and the four delta specs for observable behavior. V1 must create a credible database-first baseline without Redis or messaging, while leaving later phases free to add admission control and asynchronous processing without redefining the source of truth.

The implementation targets Java 21, Spring Boot, MyBatis, MySQL/InnoDB, Flyway, JUnit, Testcontainers, Micrometer, and Docker Compose. It is one deployable application organized by business capability; the expiration runner is a separately invocable application boundary in the same codebase, not a microservice.

## Goals / Non-Goals

**Goals:**

- Make the ten project invariants concrete as database constraints, conditional transitions, and automated verification.
- Keep transaction boundaries visible enough to explain from SQL and committed state, without ORM lifecycle behavior obscuring them.
- Compare inventory-control strategies under an identical contract and workload while selecting one safe default.
- Make every ambiguous HTTP, payment, and worker failure retry-safe.
- Produce a V1 architecture that later Redis and MQ phases extend rather than replace.

**Non-Goals:**

- Real authentication, authorization, payment providers, product catalog, cart, multi-line orders, seat selection, fulfillment, or automatic refunds.
- Redis caching, distributed locks, RocketMQ, Outbox, CDC, service discovery, or multi-service deployment.
- Database or broker high availability, horizontal scaling claims, or a preselected throughput target.
- Keeping the unsafe inventory strategy available in any normal application profile.

## Decisions

### 1. Use a single deployable modular monolith organized by capability

Use one build and one Spring Boot deployment with package-by-capability boundaries for ordering, inventory, payment, expiration, and verification. Domain services own transactions; web and scheduled adapters do not open or extend transactions implicitly.

This keeps local transactions real and inspectable while avoiding premature network boundaries. A Maven multi-repository microservice layout was rejected because it adds deployment and tracing complexity without improving any V1 invariant. Spring Modulith is not required initially; package-boundary tests can be added only if ordinary dependency discipline proves insufficient.

### 2. Use MySQL as the only business source of truth

All acceptance, order, reservation, payment, and compensation results become observable only after a MySQL transaction commits. Application memory can optimize reads but cannot authorize stock or state transitions.

The default isolation level remains InnoDB `REPEATABLE READ` so experiments reflect a common MySQL deployment. Correctness comes from indexed unique constraints, row-level locking, and conditional writes rather than an assumption that the isolation level serializes the whole workflow.

PostgreSQL was rejected for V1 because the project is intended to demonstrate MySQL/InnoDB locking and transaction behavior. Supporting both databases was rejected because SQL and locking semantics are part of the subject under study.

### 3. Model one activity SKU and quantity one per order

An order snapshots activity SKU, user, unit price, currency, and expiration time. Quantity is fixed at one. `PENDING_PAYMENT` and `PAID` are effective orders; `CLOSED_UNPAID` is not.

Multi-line orders and selectable quantity were rejected because they introduce lock ordering across stock rows and partial-allocation policy before the single-resource invariants are proven. They can be proposed later as a separate change.

### 4. Enforce active-purchase uniqueness with a claim table

`purchase_claim` has primary key `(activity_sku_id, user_id)` and unique `order_id`. The row exists exactly while the associated order is effective. Order creation inserts it; unpaid closure removes it in the same transaction that releases inventory.

A unique index involving `orders.status` was rejected because it cannot directly express uniqueness over only the effective subset in MySQL. A permanent unique key on all historical orders was rejected because V1 allows a user to retry after an unpaid order closes.

### 5. Use a stock snapshot plus immutable movement ledger

`activity_sku_stock` stores `initial_stock`, `available_stock`, `reserved_stock`, `sold_stock`, and `version`, with non-negative and conservation checks. `inventory_reservation` stores one reservation per order with `RESERVED`, `CONFIRMED`, or `RELEASED`. `inventory_movement` records `RESERVE`, `CONFIRM`, or `RELEASE` and has a unique originating operation identifier.

The snapshot is used for fast decisions; the ledger explains committed changes and supports invariant diagnostics. Event sourcing was rejected: orders and stock snapshots remain authoritative relational state, and the ledger is an audit aid rather than a reconstruction requirement.

### 6. Make conditional atomic update the normal inventory strategy

The normal runtime path reserves with one conditional write equivalent to decreasing available and increasing reserved only when available is positive. A zero-row result is the sold-out business outcome.

Three alternatives remain behind the same laboratory contract:

- Pessimistic: indexed `SELECT ... FOR UPDATE`, then update in the same transaction.
- Optimistic: version-checked update with a small configurable retry budget and an explicit retryable-contention outcome when exhausted.
- Unsafe: unprotected read followed by write, available only in the lab profile.

The strategies share business setup and invariant checks, but the application does not present runtime strategy selection to normal API callers. A Redis lock or database advisory lock was rejected because neither is needed to preserve a single InnoDB row invariant.

### 7. Define the order transaction as one atomic unit

The order application transaction performs these logical effects:

1. Resolve or claim the scoped idempotency key and validate payload consistency.
2. Verify the activity accepts orders.
3. Claim `(activity_sku_id, user_id)` for an effective purchase.
4. Reserve one unit using the selected safe strategy.
5. Insert the pending-payment order and its reserved inventory record.
6. Append the reserve movement and complete the idempotency result.

Any business rejection commits only the durable idempotency result where appropriate; any technical failure rolls back all business effects. Unique-key races are translated to stable existing-order or idempotency outcomes, never generic server errors when the winner committed normally.

### 8. Keep payment truth separate from whether it applied to the order

`payment` records provider transaction status, while an application outcome distinguishes `APPLIED` from `REFUND_REQUIRED`. `payment_callback_event` has a unique provider event key. A successful callback for a pending order locks and verifies the order, confirms its reservation, moves reserved stock to sold, marks the order paid, and records payment in one transaction.

If the order is already closed, the callback records the provider success and creates one `compensation_case` with type `LATE_PAYMENT_REFUND_REQUIRED`; it does not revive the order or alter inventory. V1 exposes this case for manual recovery and metrics. Automated refund execution is deferred.

Treating a late provider success as payment failure was rejected because it hides a real financial obligation. Reviving the order was rejected because its stock may already have been allocated elsewhere.

### 9. Serialize payment and expiration through the order row

Payment and expiration both lock the target order first and then re-check state. For an eligible unpaid expiration, the same transaction closes the order, releases the reservation, updates stock, appends the release movement, and deletes the purchase claim. For payment, the same transaction marks payment and order success and confirms stock.

The winning transaction determines the legal outcome. Every existing-order workflow uses a consistent lock order: order, stock snapshot, reservation, then claim or auxiliary rows. The scanner processes bounded batches and may use `SKIP LOCKED` to let multiple workers avoid waiting on the same orders.

A delayed in-memory task was rejected because a restart could lose expiration. A single unbounded cleanup transaction was rejected because it would hold locks too long.

### 10. Persist and return idempotency outcomes

`idempotency_record` is uniquely keyed by operation, caller, and idempotency key; it stores a canonical request hash, completion status, business result code, and resource identifier. Reuse with a different payload is a conflict. A retry after commit but before HTTP response receives the committed result.

The idempotency record does not cache arbitrary HTTP serialization. Transport responses are reconstructed from the durable business result so response-schema changes do not corrupt stored records.

### 11. Keep the external API intentionally small

V1 exposes:

- `POST /api/v1/orders` with an `Idempotency-Key`, user identifier, and activity SKU identifier.
- `GET /api/v1/orders/{orderId}` for recovery and inspection.
- `POST /api/v1/simulated-payments/callbacks` for test-only provider callbacks.
- Health and metrics endpoints with management exposure restricted to the local environment.

Order creation returns stable business codes such as `CREATED`, `SOLD_OUT`, `ACTIVITY_NOT_ACTIVE`, `EXISTING_EFFECTIVE_ORDER`, `IDEMPOTENCY_CONFLICT`, and `RETRYABLE_CONTENTION`. It does not expose SQL errors or lock implementation details.

### 12. Use Flyway migrations and explicit relational constraints

The initial schema includes activity, activity SKU stock, order, purchase claim, reservation, inventory movement, payment, payment callback event, idempotency record, and compensation case tables. Monetary values use fixed precision and orders snapshot price and currency. Timestamps are stored in UTC with microsecond precision.

Required indexes include order number uniqueness, order expiration scans, reservation by order, provider event and transaction uniqueness, idempotency scope uniqueness, purchase claim primary key, and movement operation uniqueness. Foreign keys protect ownership relationships; check constraints protect non-negative balances and stock conservation.

Application validation remains necessary for useful errors, but it is not a substitute for database constraints under concurrency.

### 13. Verify correctness with real infrastructure and controlled interleavings

JUnit unit tests cover state-transition rules. Testcontainers integration tests use real MySQL and run Flyway migrations. Concurrency tests coordinate threads at known transaction boundaries for duplicate requests and payment-versus-expiration races. A post-run invariant checker queries committed database state.

The experiment report records workload, strategy, counts, percentiles, conflicts, final balances, and invariant outcomes. k6 is introduced only for HTTP load characterization after deterministic correctness tests pass. A missing dependency or unavailable container marks verification blocked, not passed.

H2 and mocked repositories were rejected for lock and isolation tests because they cannot establish InnoDB behavior.

### 14. Add only V1-relevant observability

Micrometer records order outcomes, transaction duration, stock-strategy conflicts, idempotency hits, expiration results, late payments, and invariant-test outcomes. Structured logs carry request, idempotency, order, user, activity SKU, and payment identifiers without logging secrets.

Grafana dashboards are deferred until the metrics exist and are meaningful. V1 may expose Prometheus-format metrics through Docker Compose, but dashboard volume is not a completion criterion.

## Risks / Trade-offs

- [A single hot stock row serializes successful reservations] → Measure lock wait and latency per strategy; accept this as the V1 baseline before adding Redis admission control.
- [Optimistic locking can amplify load under extreme contention] → Bound retries, add jitter if needed, report retry exhaustion explicitly, and do not hide it as sold out.
- [A claim row can become stale if transaction boundaries are wrong] → Create or delete it only inside the same transaction as order and inventory effects; include cross-table invariant queries.
- [Expiration and payment can deadlock if lock order drifts] → Centralize transaction orchestration, use a documented lock order, capture deadlock evidence, and apply only bounded retries to whole transactions.
- [Late payment creates a financial obligation V1 does not automatically execute] → Persist a unique compensation case, expose a metric and runbook, and require manual closure until the refund phase.
- [Database check constraints may not explain failures clearly] → Validate at the service boundary and map constraint violations while retaining constraints as the final guard.
- [Laboratory strategies can leak into production configuration] → Package and profile-gate the unsafe strategy, add a startup rejection test, and omit any public strategy-selection parameter.
- [Local Docker results can be overstated] → Record hardware, container limits, dataset, concurrency, and test duration with every report; make no production-scale or HA claim.

## Migration Plan

1. Create the Java build, application configuration, and Docker Compose MySQL environment.
2. Apply the initial Flyway migration to an empty V1 database and load explicit test fixtures.
3. Implement the safe conditional-update path first, followed by payment and expiration transactions.
4. Add the alternative laboratory strategies only after the default path passes invariant tests.
5. Run the full deterministic and Testcontainers suite, then execute a documented local load experiment.

Rollback during V1 is destructive only for disposable local data: stop the application and containers, preserve experiment reports if needed, and recreate the database from Flyway migrations. No production data migration or backward compatibility guarantee is part of this change.

## Open Questions

- Select the exact supported Spring Boot, MyBatis, and MySQL container patch versions during build setup and record them in dependency management; this does not change the behavioral contract.
- Choose the default unpaid expiration duration for local demonstrations through configuration; tests use an injected clock and do not depend on wall-clock waiting.
