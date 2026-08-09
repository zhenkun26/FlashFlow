# Current verification status

Status vocabulary:

- `PASS`: command executed and assertions passed.
- `FAIL`: command executed and failed.
- `BLOCKED`: required runtime or infrastructure was unavailable.
- `NOT_RUN`: available but not executed yet.

Current workspace snapshot (2026-08-09):

| Gate | Status | Evidence |
|---|---|---|
| OpenSpec strict validation | PASS | Post-archive `openspec validate --all --strict`: 4 main specs passed; no active changes remain |
| Java compilation | PASS | Java 21 release target compiled 68 production source files and 18 test source files on OpenJDK 26.0.2 |
| Maven unit and integration tests | PASS | `mvn -o -Dmaven.repo.local=/private/tmp/flashflow-m2 test`: 37 tests, 0 failures, 0 errors, 0 skipped |
| Testcontainers MySQL tests | PASS | MySQL 8.4.6 migration, constraint, transaction, rollback, and state-race tests passed |
| Safe strategy excess-demand suite | PASS | Conditional atomic, pessimistic, and optimistic strategies each passed the coordinated 25-demand/5-stock invariant suite |
| Direct unpaid-closure retry and retry-exhaustion regressions | PASS | Focused integration tests prove follow-up ordering and no partial effects after deterministic retry exhaustion |
| V1.5 controlled k6 matrix | PASS | 7/7 cases completed with reconciled outcomes, zero unexpected responses, and valid committed state; see [V1.5 local report](2026-08-09-v1-5-local.md) |
| Optional MySQL diagnostics | PASS | Timestamped row-lock counters and `SHOW ENGINE INNODB STATUS` captured in the final baseline raw run |
| V1.6 deterministic FK upgrade fixture | PASS | Two observed `S,REC_NOT_GAP` locks, one recognized old-sequence deadlock victim, and a two-commit stock-first control with valid invariants |
| V1.6 claim-race semantics | PASS | Deterministic rollback, bounded replay/exhaustion, and sold-out current-read precedence tests passed |
| V1.6 old/new local comparison | PASS | Old: 4,598 transient retries and 927 exhausted requests; stock-first: zero transient retry or exhaustion; both committed 100 valid effects; see [V1.6 local report](2026-08-09-v1-6-local.md) |

These PASS statuses are execution evidence, not source-inspection claims. V1.5 and V1.6 runs disclosed a dirty worktree, and their local Docker/k6 results do not establish a production QPS, availability, or capacity target. One earlier V1.6 attempt remains recorded as `BLOCKED` due to a stale Docker socket. The earlier [V1 report](2026-08-08-v1-local.md) remains available as historical evidence.
