## Why

V2.1 proves the contracts and pinned RocketMQ topology needed for asynchronous ordering, but the normal application still has no live producer, consumer, asynchronous HTTP route, or broker-backed expiration trigger. V3 should activate those boundaries while preserving MySQL authority, Redis fail-closed admission, synchronous compatibility, and an explicit limitation that direct publication is not an Outbox guarantee.

## What Changes

- Add an explicitly selected V3 runtime mode with a real RocketMQ producer and at-least-once consumer; keep the existing synchronous endpoint available without a broker dependency.
- Add a public asynchronous order endpoint that returns `202 Accepted` only after broker acknowledgement, plus a bounded command-status endpoint backed by durable command and MySQL evidence.
- Classify definitive publication failure separately from ambiguous publication, reusing the stable command identity and safely releasing or quarantining Redis admission capacity according to the existing decision matrix.
- Add bounded producer and consumer retry behavior, poison/unsupported-envelope handling, a dead-letter path with inspectable evidence, and acknowledgement only after a recoverable terminal result.
- Publish delayed expiration triggers after a new order commits, consume them through the existing locked expiration boundary, and retain the database scanner as recovery for missing or late messages.
- Add revision-bound integration, fault, compatibility, reconciliation, and local characterization gates for the live application path.
- Preserve the V3/V4 boundary: V3 uses direct broker publication and does not claim eventual publication across a process crash; Transactional Outbox, CDC, polling dispatch, publication leases, and broker-confirmation recovery remain deferred.

## Capabilities

### New Capabilities

- `rocketmq-order-runtime`: Live RocketMQ runtime configuration, direct publication, at-least-once consumption, acknowledgement, bounded retry, dead-letter handling, and operational visibility.

### Modified Capabilities

- `asynchronous-order-contract`: Activate the V2.1 command contract through public asynchronous acceptance and status resources while retaining honest direct-publication ambiguity.
- `redis-admission-control`: Apply confirm, release, and quarantine transitions to real producer and consumer outcomes.
- `payment-and-expiration`: Publish and consume delayed expiration triggers while retaining the committed-state scanner as the recovery authority.
- `messaging-readiness-experiments`: Extend synthetic readiness evidence into revision-bound live application traffic and fault drills.
- `concurrency-experiments`: Reconcile HTTP acceptance, broker delivery, command outcomes, Redis lifecycle, and committed MySQL effects without presenting message throughput as production capacity.

## Impact

- Adds the pinned RocketMQ Java client to the V3 application graph, configuration guards, topics, consumer groups, producer/consumer adapters, and local Compose/Testcontainers wiring.
- Adds `/api/v2/orders` and a command-status resource while preserving `/api/v1/orders` behavior.
- Extends command-ledger transitions, Redis admission orchestration, expiration publication, metrics, experiment manifests, reports, runbooks, architecture decisions, and verification documentation.
- Introduces RocketMQ as an explicit availability dependency only when V3 mode is selected; rollback returns to the broker-free synchronous mode without a business-data migration.
