# FlashFlow

FlashFlow is a database-first limited-stock ordering laboratory. V1 establishes synchronous MySQL/InnoDB correctness; V2 adds Redis Lua admission; V2.1 adds transport-neutral command, consumer, publication-ambiguity, and delayed-expiration seams needed before a future V3 RocketMQ runtime. MySQL remains the sole durable business source of truth.

## Current V2.1 scope

- Java 21, Spring Boot, MyBatis, MySQL/InnoDB, Redis Lua, Flyway, JUnit, Testcontainers, Micrometer, and k6.
- One activity SKU and quantity one per order.
- Synchronous ordering, request idempotency, one effective order per user/SKU, inventory reservation, simulated payment, expiration, and late-payment compensation records.
- Four inventory strategies: conditional atomic update (normal default), pessimistic lock, optimistic lock, and an unsafe read-then-write laboratory control.
- Redis admission IDs are privacy-preserving digests; atomic scripts enforce generation, capacity, replay, per-user activity, confirmation, release, and quarantine.
- Fenced reconciliation rebuilds Redis only from committed MySQL facts and emits append-only evidence.
- A versioned command contract, non-Outbox lifecycle ledger, idempotent in-process consumer, and shared delayed-expiration boundary are implemented and tested while public ordering remains synchronous.
- RocketMQ 5.3.4 exists only in an isolated compatibility-spike Compose profile; there is no live broker client in the normal application graph.
- No public V3 asynchronous route, Outbox, CDC, real payment provider, automated refund execution, microservices, automatic fail-open, or production-scale claim.

## Invariants

1. Inventory balances never become negative.
2. Effective orders never exceed initial stock.
3. One user has at most one effective order for an activity SKU.
4. One scoped idempotency key has at most one business effect.
5. Orders and reservations only follow legal state transitions.
6. Repeated payment callbacks apply payment at most once.
7. Expired unpaid reservations are eventually released.
8. Repeated worker execution does not repeat a business effect.
9. The stock snapshot and immutable movement ledger remain explainable together.
10. An interrupted local transaction exposes no permanent partial order or inventory effect.

## Architecture

```text
HTTP adapter                 Scheduled adapter
     |                              |
     v                              v
durable replay -> Redis admission   Expiration application
                    |                       |
                    v                       |
             Ordering application          |
Payment application                 |
     |                              |
     +--------- explicit transaction boundaries --------+
                                                            |
       new order: stock -> order -> claim -> reservation    |
 existing order: order -> stock -> reservation -> claim    |
                                                            v
                                                     MySQL/InnoDB
```

The safe new-order transaction reserves stock before inserting stock-referencing child rows, then persists the order, purchase claim, reservation, stock movement, and result atomically. Payment and expiration operate on existing orders and lock the order first; whichever transaction commits first determines the legal terminal outcome.

See [V2.1 messaging-readiness architecture](docs/architecture/v2-1-messaging-readiness.md), [V2.1 runbook](docs/runbooks/v2-1-messaging-readiness.md), [V2 admission architecture](docs/architecture/v2-redis-admission.md), [transaction boundaries](docs/architecture/transaction-boundaries.md), and [decisions](docs/DECISIONS.md).

Release-by-release changes and their evidence boundaries are recorded in the [changelog](CHANGELOG.md).

## Local run

Prerequisites: Java 21, Maven 3.6.3+, Docker with Compose, and optionally k6.

```bash
docker compose up -d mysql
mvn test
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

If port 3306 is already occupied, set `FLASHFLOW_MYSQL_PORT=3307` for Compose and point `FLASHFLOW_DB_URL` at port 3307 when starting the application.

The commands above explicitly retain `MYSQL_ONLY` behavior. To enable V2, start `mysql redis`, set `FLASHFLOW_ADMISSION_MODE=REDIS_LUA` and a 32+ character `FLASHFLOW_ADMISSION_IDENTITY_SECRET`, then initialize the SKU generation as described in the V2 runbook. Redis failure is fail-closed; it never falls back to an unadmitted MySQL attempt.

Load disposable demonstration data:

```bash
docker compose exec -T mysql mysql -uflashflow -pflashflow flashflow < scripts/demo-data.sql
```

Create an order:

```bash
curl -i -X POST http://127.0.0.1:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-key-1' \
  -d '{"userId":"demo-user-1","activitySkuId":"demo-sku"}'
```

The simulated callback endpoint exists only under `local`, `test`, or `lab` profiles.

## Verification

Correctness gates, in order:

1. Unit state-machine checks.
2. Flyway and constraint checks against Testcontainers MySQL.
3. Deterministic race and idempotency checks.
4. Excess-demand invariant suites for all safe strategies.
5. HTTP characterization with k6 only after gates 1-4 pass.

Run k6 after tests pass:

```bash
k6 run -e SKU_ID=demo-sku -e VUS=20 -e DURATION=30s load-tests/synchronous-orders.js
```

Every experiment report must include machine/container limits, dataset, duration, concurrency, result counts, latency percentiles, conflict counts, final stock balances, and invariant results. Local Docker results are not evidence of production high availability or a universal QPS figure.

Current execution evidence is recorded in [verification status](docs/verification/current-status.md), the [V2.1 local readiness report](docs/verification/2026-08-09-v2-1-local.md), the [V2 Redis admission local report](docs/verification/2026-08-09-v2-local.md), and the earlier dated reports. No check is considered passed merely because its source file exists.

The isolated broker topology probe is reproducible with `scripts/run-rocketmq-spike.sh`; it writes append-only evidence below `reports/messaging/` and never enables messaging in the normal application profile.
