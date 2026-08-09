## Context

See `proposal.md` for motivation. The V1.5 baseline captured two order transactions that each held a shared record lock on the same `activity_sku_stock` primary-key row and then waited to upgrade it for the conditional stock update. The current sequence inserts `orders` and `purchase_claim` rows, whose foreign keys reference the stock row, before invoking the reservation strategy. All business effects currently share one transaction, and whole-transaction retry is already bounded.

The design must retain MySQL/InnoDB foreign keys, the synchronous API, one effective order per user/SKU, request idempotency, and atomic order/claim/reservation/movement persistence. Payment and expiration continue to lock existing orders before stock; a new-order transaction must not wait on an existing order while holding the stock row.

## Goals / Non-Goals

**Goals:**

- Prove the observed foreign-key shared-lock to stock exclusive-lock upgrade cycle with a deterministic database interleaving.
- Make the successful reservation operation the first write that references or exclusively controls the stock row in every safe ordering strategy.
- Preserve existing business-result precedence, especially `EXISTING_EFFECTIVE_ORDER` versus `SOLD_OUT`, under same-user races.
- Compare the old and candidate sequences with bounded, attributable correctness and characterization evidence.

**Non-Goals:**

- Eliminate every possible InnoDB deadlock or promise zero retryable responses.
- Remove foreign keys, weaken constraints, split the business effect across transactions, or add Redis/MQ/distributed locks.
- Make every strategy use `SELECT ... FOR UPDATE`, which would erase the intended optimistic and conditional strategy differences.
- Increase retry budgets as a substitute for correcting lock order.

## Decisions

### 1. Prove the old cycle separately from application acceptance tests

Add a laboratory integration test using two explicit database transactions and coordination barriers. Each transaction inserts a distinct order referencing the same stock row, pauses after the foreign-key child insert, and then both attempt the stock update. The test expects one deadlock victim and records the involved stock primary-key lock modes. This is an expected-failure control, not a passing-path workload inference.

Add a separate application-level coordinated test for the candidate sequence. It must assert completion, bounded attempts, and all committed invariants; it must not pass merely because a random run happened not to deadlock.

Alternative considered: rely on k6 plus `SHOW ENGINE INNODB STATUS`. Rejected because it demonstrates occurrence but not a repeatable causal boundary.

### 2. Move reservation before every stock-referencing child insert

Within a fresh complete transaction, use this logical sequence:

1. Claim or replay the request idempotency record.
2. Read and validate the activity/SKU and perform a non-locking effective-claim precheck.
3. Invoke the selected safe reservation strategy against stock.
4. Only after reservation succeeds, insert the order and purchase claim, followed by reservation and movement rows.
5. Complete the idempotency result in the same transaction.

Conditional atomic update remains a searched update, optimistic remains read-plus-versioned compare-and-set, and pessimistic remains `FOR UPDATE` plus update. A successful stock update retains its exclusive row lock until commit, so subsequent foreign-key checks occur after exclusive control is established and are compatible within the owning transaction.

Alternative considered: add a universal `SELECT ... FOR UPDATE` before the existing sequence. Rejected because it would make the conditional and optimistic strategies operationally pessimistic.

Alternative considered: remove the foreign keys responsible for shared locks. Rejected because referential integrity is part of the database-first correctness model.

### 3. Resolve the same-user race by rolling back the complete attempt

The precheck avoids stock work when a claim is already visible, but it cannot eliminate the race between two same-user requests. If reservation succeeds and the subsequent unique claim insert loses, throw a bounded internal claim-race signal that rolls back the entire transaction, including stock. Replay the complete command in a new transaction; the winner's claim is then visible and the stable `EXISTING_EFFECTIVE_ORDER` result can be committed to the request's idempotency record.

If reservation reports sold out, recheck the effective claim with a locking current read before committing `SOLD_OUT`. A second ordinary consistent read is insufficient under `REPEATABLE READ` because it remains on the snapshot established by the initial precheck and cannot see a claim committed while the attempt waited on stock. At this point the ordering transaction has not mutated stock, and expiration acquires stock before claim, so the current read must be covered by a coordinated cross-flow regression to confirm it introduces no reverse lock dependency. This preserves the existing result precedence when another same-user transaction committed during contention.

The claim-race replay is bounded and measured separately from database deadlock retry. It never retries only an individual SQL statement.

Alternative considered: reverse the stock mutation in the same transaction after claim conflict. Rejected because it commits avoidable version churn and makes the ledger explanation less direct.

Alternative considered: return `SOLD_OUT` immediately after a zero-row reservation. Rejected because it can change a same user's stable result from `EXISTING_EFFECTIVE_ORDER` to `SOLD_OUT` depending on timing.

Alternative considered: repeat the non-locking claim query. Rejected because MySQL `REPEATABLE READ` serves both ordinary reads from the same transaction snapshot.

Alternative considered: change ordering transactions to `READ COMMITTED`. Rejected because it broadens visibility semantics for the entire transaction to solve one bounded race.

### 4. Treat improvement as a controlled evidence claim

Extend the experiment manifest with an explicit transaction-sequence factor or retain a checked-in before/after case pair using otherwise identical inputs. Reports compare deadlock/transient retry counts, retry exhaustion, created and business-rejection results, latency, pool evidence, and committed-state invariants.

Acceptance requires deterministic correctness tests and valid committed state. A lower deadlock or retry count in local Docker is characterization evidence only; it is not a production throughput or availability claim.

## Risks / Trade-offs

- [Reserving before inserting the order changes failure ordering] → Recheck claims on sold-out and roll back the whole attempt on a post-reservation claim conflict.
- [Claim-race replay could become another unbounded loop] → Give it an explicit small bound and a distinct metric/outcome; never nest unlimited retries.
- [Moving stock earlier could introduce a cycle with payment or expiration] → Ensure the new-order path never locks or updates an existing order while holding stock; add coordinated ordering-versus-expiration/payment checks if lock inspection reveals overlap.
- [The raw deadlock test may vary across MySQL versions] → Assert the coordinated lock states and one recognized deadlock victim on the pinned MySQL 8.4.6 test image; report other infrastructure as `BLOCKED` rather than weakening the assertion.
- [Serial hot-stock behavior remains] → Preserve bounded retries and report local contention honestly; this change targets the identified upgrade cycle, not the physical fact that one row is hot.

## Migration Plan

1. Add the deterministic old-sequence deadlock control and candidate-sequence invariant tests before changing production sequencing.
2. Add bounded claim-race classification and tests for existing-order precedence, sold-out precedence, rollback, and idempotent replay.
3. Reorder the safe ordering transaction without changing schema or public API.
4. Run the complete correctness gate and a controlled old/new local comparison with optional InnoDB diagnostics.
5. Update transaction-boundary and verification documents only from executed evidence.

Rollback restores the child-row-first ordering sequence and removes the claim-race replay path. No database migration or persistent-data conversion is required.
