## 1. Project Foundation

- [x] 1.1 Create the Java 21 Maven Spring Boot project with package-by-capability boundaries for ordering, inventory, payment, expiration, and shared infrastructure
- [x] 1.2 Pin compatible Spring Boot, MyBatis, Flyway, MySQL driver, Micrometer, JUnit, and Testcontainers versions through dependency management
- [x] 1.3 Add normal, test, and lab configuration profiles; make conditional atomic inventory the normal default and reject the unsafe strategy outside the lab profile
- [x] 1.4 Add Docker Compose for a single local MySQL/InnoDB instance with explicit health checks, UTC configuration, and disposable development storage
- [x] 1.5 Add application startup and Testcontainers smoke tests that prove Java configuration, database connectivity, and Flyway migration execution

## 2. Relational Schema and Persistence Contracts

- [x] 2.1 Create the initial Flyway migration for activities, activity SKU stock, orders, purchase claims, inventory reservations, and inventory movements
- [x] 2.2 Extend the migration with idempotency records, payments, payment callback events, and late-payment compensation cases
- [x] 2.3 Add primary keys, foreign keys, unique keys, check constraints, and scan indexes described in design.md, including stock non-negativity and conservation
- [x] 2.4 Implement MyBatis records and mappers with explicit SQL for reads, conditional transitions, locking reads, and invariant queries
- [x] 2.5 Add migration tests that inspect required constraints and indexes and reject invalid stock balances or duplicate business keys

## 3. Domain States and Transaction Boundaries

- [x] 3.1 Implement order, reservation, payment-application, movement, and compensation state types with explicit legal-transition validation
- [x] 3.2 Implement stable business result codes and exception-to-result mapping without exposing SQL or lock details through the API
- [x] 3.3 Implement a canonical order-request hash and scoped idempotency persistence that detects equivalent retries and conflicting payload reuse
- [x] 3.4 Add unit tests for every legal and illegal order, reservation, and payment-application transition
- [x] 3.5 Document and enforce the existing-order lock order of order, stock snapshot, reservation, then claim or auxiliary rows

## 4. Safe Synchronous Ordering

- [x] 4.1 Implement activity-window validation and single-unit order input validation using an injected UTC clock
- [x] 4.2 Implement effective-purchase claim acquisition and stable handling of concurrent claim conflicts
- [x] 4.3 Implement the conditional atomic inventory reservation and its unique immutable reserve movement
- [x] 4.4 Implement the synchronous order transaction that combines idempotency, activity validation, purchase claim, inventory reservation, order creation, and durable result completion
- [x] 4.5 Implement recovery of committed order results for sequential retries, concurrent duplicate requests, and response-loss simulation
- [x] 4.6 Add integration tests for created, inactive, sold-out, existing-effective-order, idempotent retry, and idempotency-conflict outcomes

## 5. Inventory Strategy Laboratory

- [x] 5.1 Define one laboratory inventory-strategy contract and common workload fixture shared by all four strategy experiments
- [x] 5.2 Implement the pessimistic strategy with indexed `SELECT ... FOR UPDATE` and verify the lock remains inside the ordering transaction
- [x] 5.3 Implement the optimistic strategy with version-checked updates, bounded whole-operation retry, and a distinct retryable-contention result
- [x] 5.4 Implement the unsafe read-then-write strategy only in the lab profile and add a startup test proving normal profiles cannot select it
- [x] 5.5 Implement deterministic barriers or test hooks that reproduce the unsafe interleaving without relying on random sleeps
- [x] 5.6 Run the same excess-demand invariant suite against pessimistic, optimistic, and conditional atomic strategies and prove all three safe strategies preserve committed invariants

## 6. Payment Confirmation and Late Payment

- [x] 6.1 Implement simulated callback validation and uniqueness for provider event and provider transaction identifiers
- [x] 6.2 Implement the payment-confirmation transaction that locks a pending order, confirms its reservation, moves reserved stock to sold, appends a movement, and marks payment applied
- [x] 6.3 Implement duplicate callback handling that returns the committed payment result without repeating any stock or order effect
- [x] 6.4 Implement late-success handling that records provider success, leaves the closed order unchanged, and creates one refund-required compensation case
- [x] 6.5 Add integration tests for successful payment, duplicate delivery, provider transaction reuse, illegal transition, and repeated late callbacks

## 7. Expiration and Release

- [x] 7.1 Implement a bounded query for expired pending orders with deterministic ordering and safe concurrent-worker claiming
- [x] 7.2 Implement the expiration transaction that locks and rechecks the order, closes it, releases its reservation, restores available stock, appends a movement, and deletes the purchase claim
- [x] 7.3 Implement the separately invocable expiration runner with configurable batch size and schedule while keeping the transaction in the application service
- [x] 7.4 Add interruption and repeated-scan tests proving uncommitted work remains invisible and completed releases do not repeat
- [x] 7.5 Add deterministic payment-versus-expiration tests for both commit orders and verify the losing operation performs no conflicting side effect

## 8. API and Observability

- [x] 8.1 Implement `POST /api/v1/orders` with required `Idempotency-Key` handling and stable mappings for every V1 business result
- [x] 8.2 Implement `GET /api/v1/orders/{orderId}` with order, reservation, and payment summary sufficient for ambiguous-response recovery
- [x] 8.3 Implement the local-only simulated payment callback endpoint and prevent accidental exposure in non-local configuration
- [x] 8.4 Add validation and error responses for malformed identifiers, missing idempotency keys, invalid payloads, and unknown orders
- [x] 8.5 Add Micrometer metrics for order outcomes, idempotency hits, strategy conflicts, payment outcomes, expiration outcomes, transaction latency, and late-payment compensation backlog
- [x] 8.6 Add structured correlation fields for request, idempotency, order, user, activity SKU, and payment identifiers while excluding secrets and full callback payloads

## 9. Invariant and Concurrency Verification

- [x] 9.1 Implement a committed-state invariant checker covering stock non-negativity, stock conservation, effective-order bounds, unique effective purchase, reservation/order agreement, and movement uniqueness
- [x] 9.2 Add a distinct-user excess-demand test with fixed initial stock and substantially higher coordinated concurrency
- [x] 9.3 Add same-user concurrent order tests with different idempotency keys and same-key concurrent tests with equivalent payloads
- [x] 9.4 Add duplicate confirmation, duplicate release, and conflicting terminal reservation tests
- [x] 9.5 Add experiment reporting for workload metadata, result counts, latency percentiles, lock or retry conflicts, final balances, and every invariant result
- [x] 9.6 Add a k6 synchronous-order workload only after deterministic correctness tests pass, and record environment limits without asserting a production QPS target
- [x] 9.7 Run the full unit, migration, integration, deterministic concurrency, and HTTP load checks; mark unavailable checks blocked rather than passed

## 10. Learning Evidence and V1 Handoff

- [x] 10.1 Write the V1 README section with scope, architecture, transaction boundary, state machines, reproducible commands, and measured evidence links
- [x] 10.2 Write database lab notes that explain and reproduce unsafe overselling, pessimistic locking, optimistic conflicts, conditional atomic update, index effects, and observed deadlocks
- [x] 10.3 Record architecture decisions for modular monolith, MySQL source of truth, purchase claim uniqueness, conditional atomic update, and manual late-payment compensation
- [x] 10.4 Add a runbook for expired-reservation backlog, late-payment compensation cases, invariant failure investigation, and safe local reset
- [x] 10.5 Verify no Redis, RocketMQ, Outbox, real payment, microservice, or unsafe normal-runtime path entered the V1 implementation and capture deferred work for later OpenSpec changes
- [x] 10.6 Perform a final strict OpenSpec validation and map each specification scenario to at least one automated test or explicitly documented blocked check
