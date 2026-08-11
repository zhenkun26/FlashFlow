## Why

V3 direct publication deliberately leaves a crash window between durable command preparation and trustworthy RocketMQ acknowledgement, so a request can remain `PREPARED` or `UNRESOLVED` unless the caller retries. V4 should make accepted asynchronous work recoverably publishable after process or Broker failure while preserving stable command identity, idempotent consumption, Redis fail-closed admission, and MySQL as the sole business source of truth.

## What Changes

- Add a MySQL Transactional Outbox that persists a versioned order-command envelope atomically with the durable command acceptance record.
- **BREAKING** Change the V4 asynchronous acceptance boundary so `202 Accepted` means MySQL durably committed the command and Outbox record, rather than requiring an inline RocketMQ `SEND_OK`; acceptance remains distinct from order completion.
- Add an application-owned polling dispatcher with bounded batches, database-backed leases, retry scheduling, expired-lease takeover, and broker-confirmation recording.
- **BREAKING** Rename the explicit V3 messaging configuration value from `LIVE` to `DIRECT`; `OUTBOX` selects V4 and `DISABLED` remains the default.
- Guarantee recoverable at-least-once publication for durably accepted commands across application restart and temporary Broker failure; duplicate publication continues to converge through the existing stable command identity and idempotent consumer.
- Keep `DIRECT` V3 behavior as an explicit laboratory control, add an explicit `OUTBOX` mode for V4, and retain broker-free `DISABLED` behavior and synchronous ordering compatibility.
- Extend reconciliation, metrics, runbooks, and revision-bound evidence to account for accepted, ready, leased, acknowledged, retryable, exhausted, and unresolved Outbox work against Redis admission and committed MySQL outcomes.
- Treat polling Outbox as the V4 implementation under test; Debezium/CDC comparison, automated DLQ replay, production HA, capacity, and latency-SLA claims remain outside this change.

## Capabilities

### New Capabilities

- `transactional-outbox-publication`: Defines atomic Outbox acceptance, lease-based polling dispatch, at-least-once publication recovery, bounded terminal dispositions, and retained operational evidence.

### Modified Capabilities

- `asynchronous-order-contract`: Changes V4 acceptance from inline Broker acknowledgement to durable MySQL command-plus-Outbox commit while preserving caller-scoped status and completion semantics.
- `rocketmq-order-runtime`: Adds explicit `OUTBOX` runtime behavior while retaining V3 `DIRECT` as a comparison control and `DISABLED` as the broker-free default.
- `redis-inventory-reconciliation`: Makes Outbox lifecycle evidence part of the proof required before ambiguous admission capacity can be released or rebuilt.
- `concurrency-experiments`: Requires revision-bound V3/V4 comparison and identity-level reconciliation across Outbox, RocketMQ, Redis, and MySQL failure windows.

## Impact

- Adds a Flyway-managed Outbox table, persistence mapper, transactional acceptance boundary, polling dispatcher, lease/retry state machine, configuration, metrics, and cleanup policy.
- Changes `/api/v2/orders` behavior in `OUTBOX` mode: a committed Outbox record permits `202` even before RocketMQ acknowledges delivery; `DIRECT` mode retains the V3 contract for controlled comparison.
- Reuses the existing versioned envelope, privacy-preserving `command_id`, command ledger, RocketMQ producer/consumer, Redis admission identity, and MySQL-authoritative business transactions.
- Extends Testcontainers and real RocketMQ tests, Compose-based fault drills, append-only experiment evidence, architecture decisions, operator runbooks, scenario mappings, verification status, README, changelog, and deferred boundaries.
- Introduces no CDC platform, Kafka Connect/Debezium dependency, microservice split, automated dead-letter replay, or production availability/capacity claim.
