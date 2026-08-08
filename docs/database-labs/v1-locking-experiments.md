# V1 MySQL locking experiments

Use the same activity SKU, initial stock, user distribution, connection pool, and invariant query for every strategy. Record MySQL image, Java version, host CPU/memory, container limits, duration, concurrency, counts, percentiles, lock waits, retries, final balances, and invariant results.

## Unsafe read then write

Two transactions read `available=1, reserved=0` before either writes. Both report success and both overwrite the row with `available=0, reserved=1`. The database snapshot appears conserved, but two successful order effects compete for one reservation. `UnsafeInterleavingTest` forces this interleaving with a barrier and does not use random sleeps.

This strategy exists only under the `lab` profile. Normal startup rejects it.

## Pessimistic locking

The strategy performs an indexed `SELECT ... FOR UPDATE` by the stock primary key, verifies availability, and updates while the order transaction still owns the row lock. It prevents lost updates but serializes callers on a hot SKU.

Inspect locks while the test is paused:

```sql
SELECT ENGINE_TRANSACTION_ID, OBJECT_NAME, INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_DATA
FROM performance_schema.data_locks
WHERE OBJECT_SCHEMA = DATABASE();
```

## Optimistic locking

The strategy reads stock and version, then updates with `WHERE version = ? AND available_stock >= 1`. A zero-row result with previously observed availability is a conflict, not sold out. FlashFlow rolls back and retries the complete order transaction within a configured bound.

Record attempts and retry exhaustion. High conflict rates are evidence that optimistic locking is a poor hotspot choice, even when correctness remains intact.

## Conditional atomic update

The normal strategy executes one searched update:

```sql
UPDATE activity_sku_stock
SET available_stock = available_stock - 1,
    reserved_stock = reserved_stock + 1,
    version = version + 1
WHERE id = ? AND available_stock >= 1;
```

One affected row means reserved; zero means sold out. InnoDB still locks the matched stock row, but there is no application read/write race window.

## Index and deadlock experiments

- Compare primary-key locking with a deliberately nonselective lab query and inspect `performance_schema.data_locks`.
- Capture `SHOW ENGINE INNODB STATUS` immediately after a deadlock.
- Explain both transaction lock orders and identify the cycle; do not merely raise retry counts.
- Never remove production indexes to run a lab. Use disposable schemas or a dedicated migration/profile.

## Expected assertions

- Unsafe control: an invariant violation is reproduced and labelled expected.
- Safe strategies: `available, reserved, sold >= 0`; stock conservation holds; effective orders do not exceed initial stock; claims and reservation states agree with orders.
- Missing Java, Docker, MySQL, or k6 means `BLOCKED`, not `PASS`.

