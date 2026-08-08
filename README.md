# FlashFlow

FlashFlow is a database-first limited-stock ordering laboratory. V1 is intentionally a modular monolith backed only by MySQL/InnoDB so that overselling, idempotency, transaction boundaries, row locks, order state races, and failure recovery can be demonstrated before Redis or messaging is introduced.

## V1 scope

- Java 21, Spring Boot, MyBatis, MySQL/InnoDB, Flyway, JUnit, Testcontainers, Micrometer, and k6.
- One activity SKU and quantity one per order.
- Synchronous ordering, request idempotency, one effective order per user/SKU, inventory reservation, simulated payment, expiration, and late-payment compensation records.
- Four inventory strategies: conditional atomic update (normal default), pessimistic lock, optimistic lock, and an unsafe read-then-write laboratory control.
- No Redis, RocketMQ, Outbox, real payment provider, automated refund execution, microservices, or production-scale claim.

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
Ordering application        Expiration application
Payment application                 |
     |                              |
     +--------- explicit transaction boundaries --------+
                                                            |
         order -> stock -> reservation -> claim/aux locks   |
                                                            v
                                                     MySQL/InnoDB
```

The safe order transaction persists idempotency, order, purchase claim, reservation, stock movement, and the result atomically. Payment and expiration both lock the order first; whichever transaction commits first determines the legal terminal outcome.

See [transaction boundaries](docs/architecture/transaction-boundaries.md), [database experiments](docs/database-labs/v1-locking-experiments.md), and [decisions](docs/DECISIONS.md).

## Local run

Prerequisites: Java 21, Maven 3.6.3+, Docker with Compose, and optionally k6.

```bash
docker compose up -d mysql
mvn test
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

If port 3306 is already occupied, set `FLASHFLOW_MYSQL_PORT=3307` for Compose and point `FLASHFLOW_DB_URL` at port 3307 when starting the application.

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

Current execution evidence is recorded in [verification status](docs/verification/current-status.md). No check is considered passed merely because its source file exists.
