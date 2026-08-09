## Why

The committed stock-first MySQL baseline preserves FlashFlow's ordering invariants, but every request still reaches the hot inventory row even after demand greatly exceeds stock. V2 needs a deliberately bounded Redis admission layer that sheds obviously ineligible demand while proving that cache loss, timeout, compensation, or drift cannot override committed MySQL truth.

## What Changes

- Add an atomic Redis Lua admission protocol for one activity SKU and quantity one that checks activity admission state, prevents duplicate active tokens for one user/SKU, and decrements an available token in one script execution.
- Keep the existing synchronous MySQL ordering transaction as the only authority that creates orders, purchase claims, reservations, and inventory movements.
- Add idempotent token confirmation and compensation semantics for committed success, committed business rejection, definite pre-commit failure, and ambiguous Redis/MySQL outcomes.
- Add Redis/MySQL reconciliation that detects missing, excess, orphaned, and stale admission state and converges it without manufacturing MySQL inventory or order effects.
- Define explicit fail-closed and recovery behavior for Redis unavailability, script timeout, restart, eviction, and partially initialized activity state.
- Extend controlled experiments to compare the MySQL-only stock-first baseline with Redis admission under the same business dataset and to classify Redis decisions, fallback prevention, compensation, drift, and reconciliation separately.
- Keep the public order endpoint synchronous and compatible; defer RocketMQ, asynchronous acceptance, delayed close, Outbox, automated refunds, microservices, and production-capacity claims.

## Capabilities

### New Capabilities

- `redis-admission-control`: Atomic Redis Lua token admission, per-user active-token semantics, lifecycle operations, and bounded behavior when Redis cannot return a trustworthy decision.
- `redis-inventory-reconciliation`: Detection, reporting, and safe convergence of Redis admission state against committed MySQL orders, claims, reservations, and inventory.

### Modified Capabilities

- `synchronous-ordering`: Route eligible V2 requests through Redis admission while retaining durable MySQL outcomes, idempotent replay, one-effective-order enforcement, and compatible synchronous responses.
- `inventory-reservation`: Clarify that Redis may decline or defer admission but cannot assert committed inventory truth or authorize an effective order.
- `concurrency-experiments`: Add controlled MySQL-only versus Redis-admission comparisons and failure drills with attributable Redis and committed-state evidence.

## Impact

- Adds Redis and its Java integration as optional V2 infrastructure, Lua scripts and versioned key conventions, configuration/startup guards, bounded metrics, and local Docker/Testcontainers support.
- Changes the ordering application orchestration around the existing MySQL transaction but does not weaken database constraints or move durable order/inventory state into Redis.
- Adds reconciliation and experiment adapters inside the modular monolith; it does not create a new deployable service or change payment and expiration state transitions.
