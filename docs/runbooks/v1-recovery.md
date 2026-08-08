# V1 recovery runbook

## Expired reservation backlog

1. Check `flashflow.expiration.outcome`, application errors, pool saturation, and the count of pending orders past `expires_at`.
2. Stop adding worker concurrency if lock waits or deadlocks are increasing.
3. Invoke `ExpirationRunner.runOnce()` through an approved maintenance entry point or restore the scheduler.
4. Run the committed-state invariant query after the backlog drains.
5. Do not update stock without changing order, reservation, claim, and ledger in one reviewed transaction.

## Late-payment compensation

1. Query open `LATE_PAYMENT_REFUND_REQUIRED` cases and join payment/order details.
2. Verify the provider transaction independently before manual refund.
3. Record the external refund identifier and only then mark the case resolved in a reviewed operation.
4. Never revive `CLOSED_UNPAID` or decrement stock to make the payment appear applied.

## Invariant failure

1. Stop writes for the affected SKU; preserve logs and a consistent database snapshot.
2. Run each subquery from `InvariantMapper` separately to identify the violated relation.
3. Compare inventory movements with order, reservation, and payment rows.
4. Write an incident report with the first bad operation and missing guard.
5. Repair from MySQL evidence only; V1 has no external authority.

## Safe local reset

The Compose database is disposable development data. Preserve reports if needed, then stop Compose and remove only `docker/mysql-data` before restarting. Never reuse this instruction against a production path or an unresolved variable.

