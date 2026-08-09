## Context

FlashFlow currently has deterministic concurrency tests, a small in-process `ExperimentReport`, Micrometer outcome counters, and one k6 HTTP workload. The dated V1 report demonstrates committed-state correctness under one local configuration, but the 503 response class combines contention and overload symptoms and the run cannot be reproduced as a controlled matrix without manual setup. See `proposal.md` for motivation and `specs/concurrency-experiments/spec.md` for the strengthened behavior contract.

The design must retain MySQL/InnoDB as the only business source of truth, the existing synchronous API, fixed transaction boundaries, and bounded whole-transaction retry. Experiment tooling must never enable the unsafe strategy outside the lab profile.

## Goals / Non-Goals

**Goals:**

- Make each local characterization run attributable to an immutable resolved configuration and repository revision.
- Separate application business outcomes from retry, strategy-conflict, connection-pool, and unexpected-failure evidence.
- Run controlled comparisons in which one declared factor changes at a time.
- Query committed MySQL state after traffic and make invariant validity a prerequisite for a passed report.
- Close the two clearest indirect-evidence gaps with deterministic integration tests.

**Non-Goals:**

- Build an automatic performance tuner or select a universal best configuration.
- Establish production throughput, availability, or capacity targets from local Docker runs.
- Add Redis, MQ, CDC, distributed tracing infrastructure, or a new persistent business table.
- Change the ordering HTTP response contract or add unbounded retries.

## Decisions

### 1. Use a checked-in manifest and a thin local orchestrator

Define a small set of named experiment cases in a checked-in manifest. Each case resolves strategy, VUs, duration, initial stock, SKU distribution, connection-pool size, retry budgets, and connection timeout. A local orchestrator validates prerequisites, starts or targets the application with the resolved configuration, seeds disposable data, invokes k6, captures metrics and database evidence, and writes a run directory.

The matrix validator rejects a comparison group that changes more than its declared factor. This protects interpretation while still allowing an operator to run one case independently.

Alternatives considered:

- Encode the full matrix in k6: rejected because k6 cannot authoritatively control application startup configuration or committed-state inspection.
- Introduce a benchmark framework such as JMH: rejected because the target is end-to-end transaction contention, not isolated JVM method performance.
- Use a general CI matrix first: deferred because local Docker reproducibility and evidence format should be stable before introducing runner-specific behavior.

### 2. Treat run evidence as an append-only directory

Each run receives a collision-resistant identifier and a directory containing the resolved manifest, environment metadata, correctness-gate reference, raw k6 summary, captured application metrics, optional MySQL diagnostic snapshots, committed-state invariant result, and a concise generated summary. Existing run directories are never overwritten.

Reports record the Git commit and whether the worktree was dirty. A dirty worktree does not invalidate a local experiment, but it must be visible so the result cannot be attributed only to the commit.

Large or transient run artifacts remain outside normal source control unless deliberately selected as dated verification evidence. The stable manifest, schemas, scripts, and curated reports belong in the repository.

Alternative considered: persist experiment results in MySQL. Rejected because experiment evidence is operational metadata, the Compose database is disposable, and adding tables would mix the subject under test with the measurement archive.

### 3. Use a bounded outcome taxonomy at the transaction boundary

Keep HTTP 201, 409, and 503 compatible, but emit measurements that identify their causes. Ordering instrumentation records transaction attempts, successful terminal outcome, optimistic conflict, transient database retry, retry exhaustion, and elapsed transaction time. Connection-pool metrics come from the existing Hikari/Micrometer integration and are captured with the same run window. Unexpected exceptions remain failures rather than being folded into an expected-status counter.

Tags must use bounded enumerations such as strategy and outcome code. Run identifiers, user identifiers, SKU identifiers, exception messages, and idempotency keys are forbidden as metric tags to avoid unbounded cardinality.

Alternative considered: expose new public HTTP error codes for every internal cause. Rejected because this iteration is an experiment-observability change, not a public API redesign; the report can correlate bounded internal counters with existing protocol responses.

### 4. Gate characterization on correctness, then inspect committed state

The orchestrator accepts a characterization run only after a current correctness-gate command succeeds for the tested workspace state. After traffic stops, it queries the committed database through a dedicated verification entry point or read-only SQL and records inventory conservation, negative-balance counts, effective orders, claims, and reservation agreement.

The final evidence state is derived mechanically:

- `PASS`: prerequisites ran, workload completed, outcomes reconcile, and all invariants passed.
- `FAIL`: an executed gate, workload assertion, outcome reconciliation, or invariant failed.
- `BLOCKED`: required runtime, tool, database, or evidence collection was unavailable.
- `NOT_RUN`: a declared case was not attempted.

A run with useful latency data but invalid or missing invariant evidence cannot be reported as passed capacity evidence.

### 5. Keep load characterization and deterministic regression evidence separate

Add focused integration tests that directly prove a user can order again after unpaid closure and that forced optimistic conflicts exhaust the configured retry budget without partial effects. Load tests remain useful for saturation shape but do not substitute for deterministic scenario evidence.

Alternative considered: infer both scenarios from the aggregate k6 503 count and existing expiration assertions. Rejected because the same visible response can have several causes and the current post-expiration test does not directly perform the follow-up order.

### 6. Capture database diagnostics opportunistically, not as a pass condition

For selected high-contention cases, capture available MySQL lock-wait counters and `SHOW ENGINE INNODB STATUS` immediately after detected deadlocks. Diagnostic capture failure is recorded but does not by itself invalidate a run whose required counters and invariant evidence are complete. Raw diagnostic evidence must be timestamped and tied to the run identifier.

This avoids promising a deadlock graph for runs in which no deadlock occurs while closing the current evidence gap when one does occur.

## Risks / Trade-offs

- [Local orchestration can be sensitive to ports and host processes] → Resolve every endpoint and port into the manifest, fail as `BLOCKED` on ambiguous ownership, and never stop unrelated processes automatically.
- [Instrumentation may perturb latency] → Keep counters bounded and lightweight, record instrumentation state, and compare only runs using the same measurement configuration.
- [A large Cartesian matrix becomes slow and noisy] → Check in a small canonical baseline plus targeted comparison groups; require explicit opt-in for broader sweeps.
- [Connection-pool failures may surface through framework-specific exception wrapping] → Classify only recognized bounded causes and retain unknown exceptions as unexpected failures instead of guessing.
- [Dirty-worktree runs are hard to reproduce] → Record the dirty flag and resolved inputs; reserve curated verification claims for committed or fully disclosed states.
- [MySQL diagnostic output can contain volatile or bulky text] → Store it as optional raw evidence and summarize only the lock cycle relevant to the run.

## Migration Plan

1. Add direct deterministic tests and the expanded measurement model without changing the API.
2. Add manifest validation, disposable-data setup, run orchestration, and invariant collection.
3. Extend the k6 workload to emit machine-readable classified counts and summary output.
4. Execute the correctness gate and a minimal baseline/one-factor comparison set.
5. Curate one dated V1.5 report and update the scenario matrix only for checks actually run.

Rollback removes the experiment tooling and additional instrumentation. No business-schema or persisted-business-state migration is required; existing V1 behavior remains valid throughout.
