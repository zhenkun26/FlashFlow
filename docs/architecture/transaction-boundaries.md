# V1 transaction boundaries

## Synchronous order

One transaction performs:

1. Claim or replay `(operation, caller, idempotency key)` and verify the canonical request hash.
2. Validate the activity window.
3. Insert a pending order and acquire the unique `(activity SKU, user)` purchase claim.
4. Reserve one stock unit using the configured strategy.
5. Insert the reservation and unique `RESERVE` movement.
6. Persist the stable business result.

Business rejections such as sold out commit only their idempotency outcome. Technical failures and optimistic conflicts roll back the entire attempt. Optimistic retries begin a new whole transaction.

## Existing-order lock order

All payment and expiration transactions use:

```text
ORDER -> STOCK SNAPSHOT -> RESERVATION -> CLAIM OR AUXILIARY ROWS
```

Do not add a path that holds one of the later locks and then requests an earlier lock. Any deadlock retry must retry the whole transaction with a strict bound.

## Payment

A successful callback for a pending order commits the provider result, `PENDING_PAYMENT -> PAID`, `RESERVED -> CONFIRMED`, reserved-to-sold stock movement, and ledger row together. A duplicate event returns its previous result.

A successful callback after `CLOSED_UNPAID` records payment as `REFUND_REQUIRED` and creates one open compensation case. It never revives the order or takes inventory from another buyer.

## Expiration

The runner scans a bounded ordered batch with `FOR UPDATE SKIP LOCKED`. For each eligible order, the same transaction commits `PENDING_PAYMENT -> CLOSED_UNPAID`, `RESERVED -> RELEASED`, reserved-to-available stock movement, ledger row, and purchase-claim removal.

The scheduler is an adapter. The transaction remains in `ExpirationService`, so direct invocation and scheduled invocation have identical semantics.

