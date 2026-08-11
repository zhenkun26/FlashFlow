# V4 Transactional Outbox runbook

## Start and migrate

1. Apply Flyway migration `V4__create_order_command_outbox.sql`; it is additive and does not backfill historical V3 rows.
2. Replace `FLASHFLOW_MESSAGING_MODE=LIVE` with `DIRECT` for the V3 control, or select `OUTBOX` for V4. `LIVE` is rejected.
3. Start MySQL, Redis, and the `messaging-live` RocketMQ Compose profile and initialize each Redis SKU generation.
4. In `OUTBOX`, configure a unique bounded lease owner, batch size, poll interval, lease longer than producer timeout plus safety margin, attempt bound, and backoff range. Keep cleanup disabled during verification.
5. Verify health, then inspect the Outbox before and after a disposable request.

## Inspect safely

Read-only queries may inspect bounded state without copying `envelope_payload`:

```sql
SELECT status, COUNT(*) AS records, MIN(next_attempt_at) AS oldest_eligible
FROM order_command_outbox
GROUP BY status
ORDER BY status;

SELECT outbox_id, command_id, status, attempt_count, next_attempt_at,
       lease_owner, lease_until, result_code, acknowledged_at, updated_at
FROM order_command_outbox
WHERE status IN ('READY', 'CLAIMED', 'RETRYABLE', 'INVALID', 'EXHAUSTED')
ORDER BY next_attempt_at, created_at, outbox_id;
```

Do not place raw user IDs, idempotency keys, payload bodies, Broker bodies, or exception text into retained reports or metric tags.

## Pause, resume, and recover

- Pause new dispatch by restarting with `FLASHFLOW_OUTBOX_DISPATCH_ENABLED=false` only after first switching away from `OUTBOX`, because startup rejects an active Outbox mode whose dispatcher is disabled. Existing rows remain durable.
- A stopped owner requires no row edit: after `lease_until`, another `OUTBOX` instance claims the row with a new token.
- Broker outage: keep the backlog, restore Broker connectivity, and let due retry rows drain. Do not ask callers to recreate accepted commands.
- `INVALID` or `EXHAUSTED`: inspect command, Outbox metadata, Redis admission, and MySQL result together. V4 provides no automatic replay; never release capacity from transport status alone.
- Historical V3 `PREPARED`/`UNRESOLVED` rows have no durable envelope and are not automatically publishable.

## Rollback

Stop new asynchronous acceptance, preserve the Outbox table, and select `DIRECT` or `DISABLED`. Do not delete or rewrite pending rows and do not infer Redis release from the rollback. Returning to `OUTBOX` resumes ready, due retryable, and expired claimed work. `/api/v1/orders` and committed MySQL business data remain available.

## Verification boundary

Run deterministic and MySQL gates before the real Broker matrix. A complete V4 report must bind command, Outbox, dispatch lease, publication, delivery, Redis lifecycle, and MySQL result by stable identity. Local Docker evidence is not exactly-once publication, CDC, automated replay, production HA, capacity, persistence, or latency-SLA evidence.
