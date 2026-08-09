## Context

FlashFlow currently executes a synchronous stock-first MySQL transaction and has a clean, attributable baseline at revision `3fd476d`: 37 tests passed, OpenSpec validated strictly, and the canonical five-second hot-SKU run committed 100 valid orders from 3,745 requests with zero retryable or unexpected outcomes. See `proposal.md` for the V2 motivation and the delta specs for required behavior.

The ordering idempotency record, `purchase_claim`, inventory balances, reservations, and movements remain committed MySQL facts. Redis is a volatile admission projection placed before a new MySQL attempt; it must reduce hot-row traffic without turning a cache decision into an order success or silently falling back during failure.

## Goals / Non-Goals

**Goals:**

- Atomically govern hot-SKU admission and one active user token with replay-safe Lua operations.
- Preserve the existing synchronous API and the stock-first MySQL transaction as final validation.
- Make every cross-store uncertainty explicit as confirmed, safely releasable, or quarantined for reconciliation.
- Rebuild Redis from an attributable MySQL snapshot without exposing partial generations.
- Produce deterministic failure evidence and a controlled before/after characterization.

**Non-Goals:**

- Guarantee cross-store atomic commit or claim exactly-once execution.
- Automatically continue through MySQL when Redis admission is impaired.
- Add MQ, asynchronous order acceptance, Outbox, CDC, distributed locks, or a separate Redis service deployment.
- Use local results as production throughput, availability, or capacity evidence.

## Decisions

### 1. Put admission before a new MySQL transaction, but replay durable MySQL results first

The application first validates the request shape and consults any already completed durable idempotency result. Only a request that may create a new business effect enters Redis admission, followed by the unchanged stock-first MySQL transaction. After MySQL returns, the application confirms, safely releases, or quarantines the token. A `NO_TOKEN` decision returns the existing retryable 503-class contract without asserting `SOLD_OUT` and without starting a MySQL transaction; a later retry with the same idempotency identity may recover or acquire admission.

This avoids requiring Redis merely to replay an already committed result and prevents an expired Redis key from hiding MySQL truth. MySQL still revalidates activity, one-effective-order, and inventory constraints because Redis state can be stale.

Alternative: run Redis before every request, including replay. Rejected because Redis loss would make an already committed durable result temporarily unrecoverable.

### 2. Use versioned, single-slot SKU generations and stable admission identifiers

All keys participating in one Lua operation share a Redis Cluster hash tag and include a schema version plus SKU generation. A small current-generation pointer identifies whether the SKU is `INITIALIZING`, `READY`, or `CLOSED`. Generation data contains remaining admission capacity, admission records keyed by a stable digest of operation/caller/idempotency identity, user-to-admission ownership, and a sorted index of held-token resolution deadlines.

The Lua acquire operation validates the exact schema/script generation, recovers an existing admission before considering capacity, enforces one active user token, and then decrements capacity and creates a `HELD` admission atomically. It returns bounded decisions such as `ADMITTED`, `REPLAY`, `USER_ACTIVE`, `NO_TOKEN`, `NOT_READY`, and `STALE_GENERATION`.

Raw user identifiers and idempotency keys are not placed in logs or metric tags. Redis identities use stable digests for lookup; reports expose only bounded counts.

Alternative: one key per token with ordinary TTL. Rejected because independent expiry can return capacity without knowing whether MySQL committed and makes atomic bounded accounting harder.

### 3. Never auto-expire held capacity back into availability

A held admission has a resolution deadline for detection, not an autonomous stock-return TTL. If its normal completion path is lost, reconciliation checks the durable idempotency result, purchase claim, and effective order before deciding whether to confirm or release it. An unresolved token remains unavailable.

This intentionally favors temporary under-admission over overselling or token inflation. Normal keys may have a retention policy only after the activity is closed and no longer serving traffic.

Alternative: return every held token after a short lease. Rejected because a pause or lost response can outlive the lease while the MySQL transaction commits.

### 4. Model lifecycle changes as compare-and-transition Lua scripts

Confirmation changes `HELD -> CONFIRMED` without increasing capacity. Pre-order safe release changes `HELD -> RELEASED`; committed unpaid closure changes the corresponding pending-order token from `CONFIRMED -> RELEASED`. Each release increases capacity once, bounded by the generation's initialized capacity. Paid orders remain `CONFIRMED`. Repeated terminal operations return their prior outcome. An operation using a stale generation cannot mutate the current one.

Business outcomes are classified rather than treated uniformly:

- committed effective order: confirm;
- inactive activity, existing order, or a proven full rollback with no effective effect: safe release;
- committed unpaid closure: release the confirmed token after the MySQL transaction commits;
- MySQL sold out after admission: quarantine and reconcile because Redis likely overstates capacity;
- unknown commit or post-commit Redis timeout: quarantine until authoritative evidence resolves it.

Alternative: always compensate every non-201 response. Rejected because a response can be lost after commit and a MySQL sold-out result is evidence that incrementing Redis may recreate known excess capacity.

### 5. Fail closed instead of automatically bypassing Redis

V2 normal runtime returns the existing retryable technical contract when admission has no token or is unavailable, ambiguous, uninitialized, or version-incompatible, and does not start a new MySQL attempt. It never translates Redis exhaustion into durable `SOLD_OUT`; only MySQL can establish that business result. A MySQL-only path remains an explicit configuration for controlled comparisons and rollback, guarded so it cannot be selected implicitly after a Redis exception.

This makes Redis an honest availability dependency while ensuring its failure cannot amplify traffic onto the database. Metrics distinguish unavailable, timeout, not-ready, and stale-generation decisions.

Alternative: fail open to MySQL. Rejected because an outage would produce a traffic surge precisely when admission protection has disappeared and would obscure the V2 failure contract.

### 6. Reconcile through fenced generation replacement

A reconciliation run acquires a bounded per-SKU maintenance fence, marks admission not ready, reads an attributable committed MySQL snapshot, resolves held admissions, constructs a new generation, and atomically publishes its pointer only after validation. Requests carrying the old generation receive `STALE_GENERATION` and cannot mutate the replacement.

The snapshot computes available admission capacity from committed MySQL availability and represents effective users as consumed ownership. Reconciliation reports excess capacity, missing capacity, orphaned/stale tokens, missing confirmations, unresolved ambiguity, and every repair action. If it cannot prove convergence, the SKU remains closed to new admission.

Alternative: patch individual Redis counters while traffic continues. Rejected as the primary recovery path because mixed observations can make the repair itself unauditable; targeted idempotent lifecycle repair is still permitted when the generation and authoritative outcome are known.

### 7. Keep V2 inside the modular monolith with explicit ports

Introduce an admission port used by ordering orchestration, a Redis Lua adapter, and a reconciliation application service/runner. The MySQL-only adapter implements the same port for baseline experiments. Docker Compose and Testcontainers add a pinned Redis version; production code does not depend on a separately deployed worker or message broker.

The boundary keeps Lua and Redis client details out of the ordering domain and leaves a clear seam for V3 to transport admitted commands asynchronously without pre-implementing that phase. Expiration publishes its Redis lifecycle action only after the existing MySQL closure transaction commits; a failed or ambiguous post-commit release becomes reconciliation work and never rolls back the committed closure.

### 8. Extend the evidence manifest rather than creating a separate benchmark

The existing experiment runner gains admission mode, Redis version/configuration, script digest, generation, lifecycle timeout, and injected-failure inputs. The canonical pair changes only admission mode. Reports reconcile Redis decisions with HTTP results, MySQL transaction starts, lifecycle results, Redis final accounting, and the existing committed MySQL invariant query.

Deterministic tests cover acquire concurrency, replay, user duplication, lifecycle idempotency, ambiguous response recovery, unavailable/not-ready behavior, stale generations, and reconciliation fixtures before any k6 characterization is considered passed.

## Risks / Trade-offs

- [Redis becomes an availability dependency] → Fail with a bounded retryable result, expose the cause, and keep an explicit operator-selected MySQL-only rollback mode.
- [A cross-store timeout leaves held capacity stranded] → Quarantine rather than guess, then resolve it from durable MySQL evidence.
- [Redis may evict or lose admission state] → Treat incomplete generations as not ready and rebuild through a fenced authoritative snapshot; configure admission keys against eviction in the lab environment.
- [Reconciliation snapshot races with new orders] → Close the SKU generation under a maintenance fence before snapshot and publish only a complete replacement.
- [Per-user Redis ownership can consume memory] → Scope data to active SKU generations, retain only bounded post-activity evidence, and measure key count and memory in reports.
- [Lua script growth can hide business logic] → Keep scripts limited to admission state transitions; activity rules, durable idempotency, order creation, and inventory truth remain in Java/MySQL.
- [A local latency improvement may be mistaken for capacity proof] → Require the same correctness gates and explicit local-only evidence labels as V1.5/V1.6.

## Migration Plan

1. Add Redis infrastructure and adapters with V2 disabled; keep MySQL-only behavior as the default during implementation.
2. Verify Lua and reconciliation behavior in isolated unit/integration tests, including Redis restart and injected timeout paths.
3. Initialize a disposable SKU generation from MySQL, enable Redis admission only in test/lab, and run deterministic end-to-end gates.
4. Run the clean canonical MySQL-only and Redis-admission comparison and retain an attributable report.
5. Enable V2 locally only after reconciliation and failure drills pass. Roll back by explicitly selecting MySQL-only mode and removing the Redis admission dependency; no MySQL data migration is required.
