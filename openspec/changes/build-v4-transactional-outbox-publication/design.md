## Context

See `proposal.md` for motivation. V3 already has a privacy-preserving stable command identity, versioned `OrderCommandEnvelope`, conditional command-ledger transitions, Redis admission quarantine, a bounded RocketMQ producer, an at-least-once idempotent consumer, caller-scoped status, and identity-level evidence. Its request path nevertheless prepares the ledger, calls the producer inline, and changes the command to `ACCEPTED` only after `SEND_OK`; the ledger stores neither an immutable publication payload nor recoverable dispatch ownership.

MySQL remains the only durable business source of truth. Redis can withhold admission capacity but cannot establish an accepted command or business result, and RocketMQ can transport work but cannot establish an order. V4 must tolerate duplicate publication rather than claim exactly-once transport, and it must remain runnable in the existing disposable MySQL 8.4.6, Redis 7.4.2, and RocketMQ 5.3.4 laboratory topology.

## Goals / Non-Goals

**Goals:**

- Make every V4 `202 Accepted` command automatically publishable after application restart or temporary Broker failure without requiring a caller retry.
- Preserve one stable envelope and consumer behavior across `DIRECT` and `OUTBOX` modes.
- Make dispatcher ownership, attempts, acknowledgement, exhaustion, backlog, and recovery attributable and testable.
- Preserve Redis fail-closed behavior and prevent transport state alone from releasing capacity.
- Produce a controlled V3/V4 comparison that isolates acceptance and recovery semantics.

**Non-Goals:**

- Exactly-once RocketMQ publication, distributed transactions across MySQL, Redis, and RocketMQ, or treating Broker state as business truth.
- CDC, Debezium, Kafka Connect, binlog parsing, or a second Outbox delivery implementation in the same change.
- Automatic replay of exhausted Outbox or DLQ records, automated refund execution, multi-service extraction, production HA, capacity, or latency-SLA claims.
- Backfilling or automatically publishing historical V3 `PREPARED` or `UNRESOLVED` rows that contain no durable envelope.

## Decisions

### 1. Add a separate immutable Outbox table keyed one-to-one by command identity

Create an `order_command_outbox` table with a generated `outbox_id`, unique `command_id`, envelope schema version, canonical serialized payload, payload fingerprint, bounded topic/tag metadata, state, attempt count, next-attempt time, lease token/owner/expiry, bounded result code, and acknowledged/created/updated timestamps. A foreign key links the row to `order_command_ledger`; the Outbox does not duplicate order result fields.

The initial state machine is:

```text
READY ──claim──> CLAIMED ──SEND_OK──> ACKNOWLEDGED
  ▲                  │
  │                  ├─definite/ambiguous failure within budget──> RETRYABLE
  │                  ├─invalid immutable envelope────────────────> INVALID
  │                  └─attempt budget exhausted──────────────────> EXHAUSTED
  │
  └──────────── expired CLAIMED lease is reclaimable ──────────────
```

`RETRYABLE` becomes claimable only at `next_attempt_at`; expired `CLAIMED` is selected directly for takeover. `ACKNOWLEDGED`, `INVALID`, and `EXHAUSTED` are not automatically claimable. Every mutation uses the current lease token or expected state so an expired owner cannot overwrite its successor.

One command has one immutable order-command Outbox row in V4. Multiple event types per aggregate would require a different sequencing and identity contract and are deferred.

Alternative: extend `order_command_ledger` with payload and lease columns. Rejected because command consumption/result convergence and transport publication have different state machines, retention, and ownership. Alternative: create a new row for every attempt. Rejected because attempts are evidence about one accepted message, not independent commands.

### 2. Commit command acceptance and Outbox readiness in one local transaction

After stable identity creation and Redis admission, an application transaction performs create-or-replay validation of the command ledger and insert-or-verify of the immutable Outbox row. A new V4 command transitions to ledger `ACCEPTED` in the same commit that makes its Outbox `READY`; only that commit permits `202`. A replay verifies command fingerprint, envelope bytes/fingerprint, schema, and routing metadata before returning the existing durable acceptance.

The transaction does not publish to RocketMQ. If it rolls back, neither record is accepted. If its client-visible commit outcome is unknown, the endpoint does not claim `202`; a same-identity retry reads MySQL and either recovers the committed pair or recreates it. Redis ambiguity continues to retain or quarantine capacity.

The ledger's `ACCEPTED` meaning becomes mode-scoped: in `DIRECT`, Broker acknowledgement; in `OUTBOX`, durable local acceptance awaiting recoverable publication. The public status remains a business-oriented bounded view and does not expose lease owner, raw payload, or exception data.

Alternative: write only an Outbox row and derive the command ledger later. Rejected because caller status and consumer convergence require the durable command identity immediately. Alternative: keep returning `202` only after inline `SEND_OK`. Rejected because that preserves the V3 client contract and hides the V4 durability boundary being evaluated.

### 3. Poll with MySQL leases and publish outside the claiming transaction

A scheduled dispatcher repeatedly opens a short transaction, selects a bounded ordered batch of eligible `READY`, due `RETRYABLE`, or expired `CLAIMED` rows using indexed predicates and `FOR UPDATE SKIP LOCKED`, assigns a random per-claim lease token and configured expiry, commits, and then publishes each item outside the database transaction. Result updates require the matching lease token.

Batch size, poll interval, lease duration, attempt limit, and bounded exponential backoff are validated configuration. Lease duration must exceed the configured producer timeout plus a safety margin. Ordering is deterministic by eligibility time, creation time, and Outbox identity, but V4 claims no global, per-SKU, or completion order because multiple dispatcher and Broker consumer threads may progress independently.

Long database transactions around Broker I/O are avoided. A lost `SEND_OK` result and a crash after Broker acceptance but before the state update both converge through at-least-once republish using the same envelope and command identity.

Alternative: hold row locks while sending. Rejected because Broker latency would extend InnoDB transactions and pool/lock pressure. Alternative: use an in-memory ownership queue. Rejected because restart would lose attribution and takeover state. Alternative: database advisory or Redis distributed locks. Rejected because the Outbox database already provides durable row ownership and Redis cannot become publication authority.

### 4. Bound automatic retries without inferring business truth

Every claimed publication increments a durable attempt count. Trustworthy `SEND_OK` records `ACKNOWLEDGED`. Definitive failure and ambiguous timeout use the same retry path because neither permits deletion; ambiguity may cause duplicate publication, which is safe under the existing consumer identity. A deterministic immutable-envelope violation becomes `INVALID`. A valid item reaching the configured attempt limit becomes `EXHAUSTED`.

`INVALID` and `EXHAUSTED` stop automatic dispatch but retain the original row and bounded cause. Neither state proves that no Broker delivery or business effect exists and neither automatically releases Redis admission. Manual inspection or a future same-identity replay workflow is outside V4.

Alternative: retry forever. Rejected because poison routing or persistent configuration failure would create an unbounded hot loop. Alternative: mark ambiguous sends acknowledged. Rejected because a timeout is not trustworthy Broker evidence. Alternative: release admission at exhaustion. Rejected because a prior ambiguous send may still be delivered or committed.

### 5. Make runtime modes mutually exclusive

Replace the current `LIVE` configuration value with explicit `DIRECT`; add `OUTBOX`; retain `DISABLED` as the default. Both active modes reuse the same RocketMQ producer, consumer, topics, groups, envelope validation, and status resource, but only `DIRECT` invokes the producer in the HTTP request and only `OUTBOX` creates the dispatcher and atomic acceptance service. Startup rejects unsupported legacy `LIVE`, missing Outbox bounds, shared/conflicting topics or groups, and contradictory scheduler settings.

Keeping modes mutually exclusive makes the acceptance contract observable and prevents a request from both publishing inline and inserting dispatchable work. Synchronous `/api/v1/orders` remains available in all modes.

Alternative: silently alias `LIVE` to `DIRECT`. Rejected because it obscures the breaking configuration migration and makes evidence less attributable. Alternative: run direct and Outbox publication simultaneously. Rejected because it intentionally duplicates every command and cannot isolate the reliability comparison.

### 6. Reconciliation treats Outbox as transport evidence, not business truth

Admission reconciliation joins command ledger facts with the one-to-one Outbox disposition. `READY`, `CLAIMED`, `RETRYABLE`, and `ACKNOWLEDGED` without a terminal command are in-flight and retain capacity. `INVALID` and `EXHAUSTED` require operator attention and also retain capacity unless existing authoritative MySQL checks independently prove that no accepted, published, in-progress, or committed effect can exist. Missing or contradictory Outbox evidence blocks convergence.

The Outbox dispatcher never writes order, inventory, reservation, payment, or expiration business tables. Consumers remain the only transport adapters that invoke existing MySQL-authoritative application boundaries.

Alternative: treat `ACKNOWLEDGED` as an order effect. Rejected because Broker acceptance is not consumption or MySQL commit. Alternative: let the dispatcher confirm Redis admission. Rejected because admission confirmation follows committed business evidence, not publication.

### 7. Retain rows through verification and clean up only proven terminal history

V4 does not hard-delete Outbox rows in the dispatch path. A separately disabled-by-default maintenance operation may later remove only `ACKNOWLEDGED` rows whose linked command has a terminal durable result and whose configured retention period elapsed. `READY`, `CLAIMED`, `RETRYABLE`, `INVALID`, `EXHAUSTED`, and any row involved in a retained report are not eligible.

The implementation and V4 evidence do not depend on cleanup being enabled. This prevents retention work from weakening the recovery proof.

Alternative: delete on `SEND_OK`. Rejected because it destroys the evidence needed to reconcile duplicate delivery, command completion, and admission state.

### 8. Extend evidence around stable identity and a clean revision

Metrics use bounded state, result class, and mode tags only. Backlog count and oldest eligible age are gauges; claims, lease takeovers, attempts, acknowledgements, retries, invalid items, and exhaustion are counters. Logs and retained reports may use privacy-preserving command/outbox identity but exclude raw caller IDs, idempotency keys, payload bodies, Broker message bodies, and exception text.

The final V4 report reconciles each accepted command with its Outbox row and publication disposition, RocketMQ delivery/acknowledgement, command result, Redis lifecycle, and MySQL effect. Required drills include transaction rollback, lost HTTP response, process stop after commit, competing claims, claim expiry, Broker outage/recovery, lost producer acknowledgement, stop after `SEND_OK`, duplicate delivery, invalid envelope, retry exhaustion, and backlog drain. All gates must bind to one clean implementation revision.

## Risks / Trade-offs

- [Polling adds database reads and writes on the source-of-truth database] → Use an eligibility index, bounded batches, `SKIP LOCKED`, short claim transactions, backoff, and evidence for query/lock pressure.
- [A lease may expire while a slow publisher is still active] → Validate lease duration against producer timeout, require lease-token updates, and rely on consumer idempotency when duplicate publication still occurs.
- [Changing `202` semantics can surprise V3 clients and operators] → Make mode explicit, document that V4 acceptance is durable queueing rather than Broker acknowledgement, and expose completion only from committed MySQL evidence.
- [Renaming `LIVE` to `DIRECT` breaks existing startup configuration] → Fail fast with a bounded migration message and update every Compose example, runbook, and verification script in the same change.
- [Outbox payloads add retained sensitive data] → Store only the existing bounded envelope, preserve privacy digests, prohibit payload logging/reporting, and define terminal retention separately from dispatch.
- [Attempt exhaustion can strand admission capacity] → Surface operator-required counts and keep fail-closed semantics; automated replay/release requires a later explicit design.
- [Outbox table growth can degrade polling] → Index eligibility, record backlog age, test a bounded backlog, and permit only conservative terminal cleanup after the V4 proof is complete.
- [V3/V4 comparison can be misread as a capacity benchmark] → Record exact local scope and compare acceptance/recovery behavior separately from throughput.

## Migration Plan

1. Apply the additive Outbox migration and indexes while the application remains in `DISABLED` or the existing V3 revision; no historical ledger row is backfilled.
2. Deploy code that understands `DISABLED`, `DIRECT`, and `OUTBOX`, with `DISABLED` still the default. Update explicit V3 environments from `LIVE` to `DIRECT` before enabling traffic.
3. Verify `DIRECT` reproduces V3 behavior and never creates or claims Outbox rows.
4. Initialize a fresh disposable V4 dataset/generation, select `OUTBOX`, and run atomicity, lease, restart, Broker-fault, duplicate, reconciliation, and backlog-drain gates.
5. Run the controlled `DIRECT`/`OUTBOX` comparison and bind the final evidence to a clean revision before updating release claims.

Rollback is configuration-only from `OUTBOX` to `DIRECT` or `DISABLED`, but operators must first stop new Outbox acceptance and allow or explicitly preserve the durable backlog. Rolling back code does not delete Outbox rows or authorize admission release. Returning to `OUTBOX` resumes eligible work; historical `ACKNOWLEDGED` rows remain evidence.
