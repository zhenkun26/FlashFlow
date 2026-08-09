# V2 Redis Lua admission boundary

V2 adds Redis as a fail-closed admission layer in front of the unchanged synchronous MySQL ordering transaction. Redis limits work entering MySQL; it does not own durable inventory, orders, uniqueness, payment, or expiration truth.

## Request and token lifecycle

```text
durable idempotency replay in MySQL
              |
              v (new/unresolved request)
Redis Lua acquire -- NO_TOKEN/NOT_READY/error --> retryable 503, no MySQL order transaction
              |
        ADMITTED / REPLAY
              v
stock-first MySQL transaction
      |            |                 |
 committed      proven reject    unknown outcome
      |            |                 |
   confirm       release           quarantine
      v            v                 v
 CONFIRMED      RELEASED       QUARANTINED/HELD
```

An admission ID and user digest are HMAC-SHA256 values derived from the scoped idempotency inputs. Raw users and idempotency keys do not enter Redis keys, admission records, reports, or metric tags. Every SKU key shares one Redis Cluster hash tag. A `HELD` deadline makes stale work detectable; it is not a TTL and never returns capacity by itself.

Unpaid expiration releases a confirmed token only from an `afterCommit` callback. Paid orders remain consumed. A Redis lifecycle failure cannot roll back a committed MySQL result and instead becomes reconciliation evidence.

## Generation model

A SKU exposes one `current` pointer and versioned generation hashes for metadata, admissions, users, and deadlines. Lua scripts validate the script version and generation. Initialization sets `INITIALIZING`; publication changes the pointer to a fully seeded `READY` generation atomically. Old-generation lifecycle calls return `STALE_GENERATION`.

Reconciliation first closes admission under a 30-second per-SKU maintenance lease, then reads a repeatable-read MySQL snapshot, seeds a replacement generation, and publishes only if no ambiguity remains. An expired lease can be taken over; a partial or unresolved generation remains not ready.

## Failure matrix

| Condition | Runtime behavior | Capacity behavior |
|---|---|---|
| Redis unavailable | Retryable 503; no new MySQL ordering transaction | No fallback and no capacity change |
| Lua timeout/lost reply | Ambiguous/retryable; same key can replay the stored token | No guessed release |
| Missing key or partial generation | `NOT_READY` | Rebuild from MySQL |
| Script version mismatch | `VERSION_MISMATCH` | Fail closed |
| MySQL rejects before commit | Safe release | Return once, bounded by initialized capacity |
| MySQL sold out after admission | Quarantine | Withhold until reconciliation |
| MySQL commit, confirm fails | Return committed result from MySQL | Reconciliation restores confirmation |
| Unpaid closure, release fails | Keep committed closure | Reconciliation proves whether capacity may return |
| Ambiguous held token | `BLOCKED` reconciliation report | Remains unavailable |

The system contains no automatic fail-open path, distributed lock, application-owned durable stock, MQ, Outbox, or CDC behavior in V2.
