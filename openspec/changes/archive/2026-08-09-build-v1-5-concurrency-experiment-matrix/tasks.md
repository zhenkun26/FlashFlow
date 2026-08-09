## 1. Direct Correctness Evidence

- [x] 1.1 Extend the payment/expiration integration coverage to place a new order after unpaid closure and assert that the released claim and stock permit exactly one new effective order.
- [x] 1.2 Add a deterministic optimistic-conflict test that exhausts the configured retry budget and asserts a retryable result with no partial order, claim, reservation, movement, or stock effect.
- [x] 1.3 Update the specification scenario matrix to reference the new direct tests only after they execute successfully.

## 2. Outcome and Contention Instrumentation

- [x] 2.1 Define bounded measurement categories for transaction attempts, transient database retries, optimistic conflicts, retry exhaustion, connection-pool acquisition failure, business result codes, and unexpected failures.
- [x] 2.2 Instrument the complete ordering transaction retry boundary and verify that every terminal request records one outcome while each attempt and retry is counted separately.
- [x] 2.3 Expose the required Prometheus and Hikari metrics for local/lab experiments without adding high-cardinality tags or changing the public ordering response contract.
- [x] 2.4 Add unit or integration tests that prove metric classification, bounded tag values, and retry-attempt accounting for representative success, rejection, conflict, exhaustion, and unexpected-failure paths.

## 3. Experiment Manifest and Validation

- [x] 3.1 Add a checked-in manifest schema covering strategy, VUs, duration, stock, SKU distribution, pool size, connection timeout, and both retry budgets.
- [x] 3.2 Define a minimal canonical baseline and targeted one-factor comparison groups for strategy, VUs, pool size, retry budget, stock level, and SKU contention shape.
- [x] 3.3 Implement manifest validation that rejects unknown values, unsafe strategy use outside the lab profile, missing required inputs, and comparison groups that change more than the declared factor.
- [x] 3.4 Add automated tests for valid independent cases, valid one-factor groups, multi-factor rejection, and unsafe-profile rejection.

## 4. Reproducible Run Orchestration

- [x] 4.1 Implement prerequisite checks for Java, Maven, Docker/MySQL, k6, application health, port ownership, and a current successful correctness gate, mapping unavailable prerequisites to `BLOCKED`.
- [x] 4.2 Implement disposable dataset preparation for configured stock and SKU distribution without deleting or resetting an unresolved database target.
- [x] 4.3 Implement case execution that resolves all configuration explicitly, runs the selected k6 workload, and never stops unrelated host processes automatically.
- [x] 4.4 Generate a collision-resistant append-only run directory containing the resolved manifest, Git revision and dirty state, timestamps, environment metadata, correctness-gate evidence, raw k6 summary, and application metrics.
- [x] 4.5 Add optional timestamped capture of MySQL lock-wait counters and `SHOW ENGINE INNODB STATUS`, recording capture failure without misclassifying an otherwise complete run.

## 5. Result Reconciliation and Reporting

- [x] 5.1 Extend the k6 workload for configured SKU distributions and machine-readable counts for created, each business rejection class, retryable 503, and unexpected responses.
- [x] 5.2 Collect committed-state inventory, order, claim, reservation, and movement evidence after traffic through read-only verification queries.
- [x] 5.3 Reconcile total requests with classified outcomes and derive `PASS`, `FAIL`, `BLOCKED`, or `NOT_RUN` mechanically from prerequisites, workload assertions, collection completeness, and invariant results.
- [x] 5.4 Generate a stable concise report with resolved inputs, latency percentiles, result counts, retry/conflict/pool evidence, final balances, invariant results, and warnings for dirty or uncontrolled environment differences.
- [x] 5.5 Add automated reporter tests for append-only output, outcome reconciliation, failed invariants, missing evidence, dirty-worktree attribution, and controlled-comparison summaries.

## 6. Verification and Curated Evidence

- [x] 6.1 Run OpenSpec strict validation and the complete Maven correctness suite, recording `FAIL` or `BLOCKED` honestly if either cannot complete.
- [x] 6.2 Execute the canonical baseline plus at least one comparison pair for each declared factor after the correctness gate passes.
- [x] 6.3 Review generated evidence for invariant validity, outcome reconciliation, uncontrolled differences, and unsupported capacity wording before selecting any run for documentation.
- [x] 6.4 Publish a dated V1.5 local verification report and refresh current verification status with the exact commands, environment, configuration, and separate created, rejection, contention, and failure counts.
- [x] 6.5 Document how to reproduce one case and a controlled comparison, including safe port overrides, evidence locations, and the rule that local characterization is not a production QPS or availability claim.
