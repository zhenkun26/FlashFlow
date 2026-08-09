# V1 transaction boundaries

## Synchronous order

One transaction performs:

1. Claim or replay `(operation, caller, idempotency key)` and verify the canonical request hash.
2. Validate the activity window.
3. Non-lockingly check the unique `(activity SKU, user)` effective-order claim.
4. Reserve one stock unit using the configured strategy.
5. Insert the pending order and acquire the unique purchase claim.
6. Insert the reservation and unique `RESERVE` movement.
7. Persist the stable business result.

If another transaction wins the claim after step 3, the complete attempt rolls back and the command is replayed within the separate claim-race bound. A sold-out result uses a current locking read of the claim so MySQL `REPEATABLE READ` does not hide a winner committed after the initial snapshot. Business rejections commit only their idempotency outcome. Technical failures, optimistic conflicts, and InnoDB deadlock victims roll back the entire attempt; all retries begin a new whole transaction.

## Proven V1.5 deadlock graph and V1.6 boundary

The deterministic two-connection MySQL fixture proves this old child-first graph on one stock primary key:

```text
T1: INSERT order(FK -> stock) -> holds S,REC_NOT_GAP -> requests X for stock UPDATE
T2: INSERT order(FK -> stock) -> holds S,REC_NOT_GAP -> requests X for stock UPDATE
                                      ^ mutual S-to-X upgrade cycle ^
```

MySQL selected exactly one deadlock victim. The coordinated stock-first candidate instead acquired the stock write lock before inserting the order, claim, reservation, and movement; both transactions completed and committed-state invariants passed. This proves removal of that specific FK shared-to-exclusive upgrade cycle. It does not prove that every possible InnoDB deadlock or retryable response is eliminated.

`CHILD_FIRST_LEGACY` remains available only under the `lab` profile to reproduce the before/after experiment. Normal and test runtime default to `STOCK_FIRST`; the startup guard rejects the legacy sequence outside `lab`.

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
