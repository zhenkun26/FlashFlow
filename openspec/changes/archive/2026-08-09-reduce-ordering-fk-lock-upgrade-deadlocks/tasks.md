## 1. Deterministic Root-Cause Evidence

- [x] 1.1 Add a two-transaction MySQL integration fixture that coordinates child-row insertion before a shared hot-stock update and asserts one recognized deadlock victim.
- [x] 1.2 Capture and assert the relevant stock primary-key shared and exclusive lock modes at the coordinated boundary without depending on random sleeps or k6 timing.
- [x] 1.3 Add a candidate stock-first coordinated test that completes without the reproduced upgrade cycle and verifies stock, order, claim, reservation, and movement invariants.
- [x] 1.4 Document the proven old-sequence lock graph separately from any inferred or still-unverified deadlock path.

## 2. Claim-Race Semantics and Measurement

- [x] 2.1 Define a bounded internal outcome for a claim committed after the initial effective-claim precheck, distinct from optimistic conflict and transient database retry.
- [x] 2.2 Add deterministic tests showing a post-reservation claim conflict rolls back the complete attempt and commits no partial stock, order, claim, reservation, movement, or idempotency effect.
- [x] 2.3 Add a deterministic retry test proving the next complete attempt observes the winning claim and returns `EXISTING_EFFECTIVE_ORDER` within the configured bound.
- [x] 2.4 Add a sold-out race test proving the command rechecks the effective claim and preserves `EXISTING_EFFECTIVE_ORDER` precedence when another same-user order committed while stock was contested.
- [x] 2.5 Extend bounded metrics and tests for claim-race rollback, replay attempts, replay exhaustion, database deadlock retry, and unexpected failure without high-cardinality tags.

## 3. Stock-First Ordering Protocol

- [x] 3.1 Add a non-locking effective-claim precheck that can return the existing committed order before stock work while preserving idempotent result completion.
- [x] 3.2 Reorder the safe transaction so conditional-atomic, optimistic, and pessimistic reservation occurs before inserting stock-referencing order and claim rows.
- [x] 3.3 Route a post-reservation unique-claim loss through complete-transaction rollback and bounded claim-race replay rather than committed stock reversal or single-SQL retry.
- [x] 3.4 Recheck the effective claim after a sold-out reservation result before committing `SOLD_OUT`.
- [x] 3.5 Verify that unsafe read-then-write remains lab-only and that no universal `FOR UPDATE` erases the safe strategies' intended distinctions.

## 4. Regression and Cross-Flow Safety

- [x] 4.1 Run and extend direct ordering tests for created, sold-out, inactive, existing-order, idempotency replay/conflict, optimistic exhaustion, and unexpected rollback paths under the new sequence.
- [x] 4.2 Re-run coordinated same-key and same-user concurrency tests and assert exactly one committed business effect.
- [x] 4.3 Re-run payment-versus-expiration, unpaid-closure reorder, and expiration rollback tests to verify the new-order stock-first path introduces no existing-order lock cycle.
- [x] 4.4 Run the safe-strategy excess-demand suite and verify committed invariants for conditional-atomic, optimistic, and pessimistic strategies.
- [x] 4.5 Run the complete Maven/Testcontainers correctness suite and OpenSpec strict validation, preserving `FAIL` and `BLOCKED` honestly.

## 5. Controlled Before/After Characterization

- [x] 5.1 Add a manifest-valid controlled comparison for the old and stock-first transaction sequences, changing no other workload or runtime factor.
- [x] 5.2 Execute the correctness gate, canonical hot-SKU comparison, and optional timestamped InnoDB diagnostic capture on the pinned local MySQL image.
- [x] 5.3 Reconcile requests, business outcomes, claim-race attempts, transient retries, retry exhaustion, pool evidence, latency, and committed-state invariants for both runs.
- [x] 5.4 Review whether any remaining deadlock has a different lock graph and avoid claiming that all InnoDB deadlocks or retryable responses were eliminated.
- [x] 5.5 Publish a dated local V1.6 report and update transaction-boundary/current-status documentation with exact commands, configuration, evidence attribution, and non-production wording.
