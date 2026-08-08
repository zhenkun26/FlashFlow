## Why

FlashFlow needs a deliberately narrow, database-first baseline that proves its core ordering invariants before Redis, messaging, or distributed failure modes are introduced. This change establishes that baseline and produces reproducible evidence that the team can explain transaction boundaries, lock behavior, idempotency, and state races rather than relying on framework or infrastructure claims.

## What Changes

- Establish a Java 21 modular-monolith backend with MySQL/InnoDB as the sole business source of truth and Flyway-managed schema.
- Add synchronous single-unit ordering for one activity SKU, with request idempotency and at most one effective order per user and activity SKU.
- Add database-backed stock reservation, confirmation, and release while enforcing non-negative stock and stock conservation.
- Add a simulated payment callback, payment idempotency, legal order transitions, timeout closure, and release of expired reservations.
- Add controlled implementations of unsafe read-then-write, pessimistic locking, optimistic locking, and conditional atomic update so their correctness and contention behavior can be compared. The unsafe strategy is test/lab-only and cannot be selected in the normal runtime profile.
- Add deterministic concurrency and integration verification using JUnit, Testcontainers, and invariant queries, plus a minimal local Docker Compose environment.
- Explicitly defer Redis, RocketMQ, Transactional Outbox, automated refunds, real payment gateways, microservices, and production-scale availability claims.

## Capabilities

### New Capabilities

- `synchronous-ordering`: Accept synchronous single-unit order requests with durable idempotency and one effective order per user and activity SKU.
- `inventory-reservation`: Reserve, confirm, and release MySQL-backed inventory while preserving non-negative balances and stock conservation.
- `payment-and-expiration`: Process repeatable simulated payment callbacks, enforce legal order transitions, and close expired unpaid orders safely.
- `concurrency-experiments`: Reproduce the unsafe read-then-write race and compare it with three correct MySQL concurrency-control strategies using deterministic tests and recorded evidence.

### Modified Capabilities

None.

## Impact

- Introduces the initial Java/Spring Boot application structure, relational schema, migrations, REST API, background expiration worker, tests, and local observability hooks.
- Adds runtime dependencies for Spring Boot, MyBatis, MySQL, Flyway, Micrometer, JUnit, and Testcontainers, plus Docker Compose for local infrastructure.
- Establishes database constraints and transaction semantics that later Redis and RocketMQ changes must preserve rather than replace.
- Does not expose compatibility-sensitive production APIs yet; API details created here form the V1 baseline for later OpenSpec changes.
