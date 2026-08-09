# Current verification status

Status vocabulary:

- `PASS`: command executed and assertions passed.
- `FAIL`: command executed and failed.
- `BLOCKED`: required runtime or infrastructure was unavailable.
- `NOT_RUN`: available but not executed yet.

Current V2 workspace snapshot (2026-08-09):

| Gate | Status | Evidence |
|---|---|---|
| OpenSpec strict validation | PASS | All five deltas were verified in main specs; 6 main specifications passed strict validation |
| Java compilation | PASS | Java 21 release target compiled 93 production source files and 26 test source files on OpenJDK 26.0.2 |
| Maven unit and integration tests | PASS | `mvn -o -Dmaven.repo.local=/private/tmp/flashflow-m2 test`: 60 tests, 0 failures, 0 errors, 0 skipped |
| Testcontainers MySQL and Redis tests | PASS | MySQL 8.4.6 plus pinned Redis 7.4.2 Lua, ordering, lifecycle, failure, and reconciliation suites passed |
| V1 compatibility with admission disabled | PASS | Explicit pre-existing test selection: 37 tests passed under `MYSQL_ONLY` |
| Safe strategy excess-demand suite | PASS | Conditional atomic, pessimistic, and optimistic strategies each passed the coordinated 25-demand/5-stock invariant suite |
| Direct unpaid-closure retry and retry-exhaustion regressions | PASS | Focused integration tests prove follow-up ordering and no partial effects after deterministic retry exhaustion |
| V1.5 controlled k6 matrix | PASS | 7/7 cases completed with reconciled outcomes, zero unexpected responses, and valid committed state; see [V1.5 local report](2026-08-09-v1-5-local.md) |
| Optional MySQL diagnostics | PASS | Timestamped row-lock counters and `SHOW ENGINE INNODB STATUS` captured in the final baseline raw run |
| V1.6 deterministic FK upgrade fixture | PASS | Two observed `S,REC_NOT_GAP` locks, one recognized old-sequence deadlock victim, and a two-commit stock-first control with valid invariants |
| V1.6 claim-race semantics | PASS | Deterministic rollback, bounded replay/exhaustion, and sold-out current-read precedence tests passed |
| V1.6 old/new local comparison | PASS | Old: 4,598 transient retries and 927 exhausted requests; stock-first: zero transient retry or exhaustion; both committed 100 valid effects; see [V1.6 local report](2026-08-09-v1-6-local.md) |
| V2 deterministic fault drills | PASS | Unavailable, timeout/lost reply, state loss, partial/version mismatch, duplicate lifecycle, cross-store uncertainty, after-commit release failure, and drift/rebuild fixtures passed |
| V2 canonical local pair | PASS | MySQL-only and Redis-admission runs each committed exactly 100 valid effects; Redis admitted 100 and avoided 21,678 MySQL attempts; see [V2 local report](2026-08-09-v2-local.md) |
| Delta spec sync and archive | PASS | Main specs synchronized and change archived as `2026-08-09-add-redis-lua-admission-control` with 46/46 tasks complete |
| Commit / push / publication | PASS | V2 release authorized and published to `origin/main` after the release gates passed |

These PASS statuses are execution evidence, not source-inspection claims. V1.5, V1.6, and V2 working runs disclosed a dirty worktree, and their local Docker/k6 results do not establish a production QPS, availability, persistence, failover, or capacity target. V2 retained two `BLOCKED` setup attempts before the canonical pair passed. The earlier [V1 report](2026-08-08-v1-local.md) remains available as historical evidence.

## Current V2.1 apply-workflow snapshot (2026-08-09)

| Gate | Status | Evidence |
|---|---|---|
| Complete Maven/Testcontainers suite | PASS | 80 tests, 0 failures, 0 errors, 0 skipped |
| Explicit synchronous `MYSQL_ONLY` selection | PASS | 24 core compatibility tests passed |
| Command and expiration deterministic drills | PASS | Contract, ledger, duplicate/interruption, synchronous race, publication ambiguity, delayed trigger, and scanner race cases passed |
| Manifest / Compose | PASS | 11 manifest cases validated and the final messaging-spike Compose configuration resolved |
| RocketMQ 5.3.4 compatibility | PASS | Final append-only report `reports/messaging/20260809T092454Z-rocketmq-spike` recorded registration, `SEND_OK`, matching consumption, and `SYNC_FLUSH` |
| OpenSpec strict validation | PASS | 7/7 active change and main specifications passed |
| Clean revision / V3 readiness | BLOCKED | Results belong to a dirty worktree based on `02d224f`; see [V2.1 local report](2026-08-09-v2-1-local.md) |
| OpenSpec sync/archive | NOT_RUN | Separate workflow after review and a clean revision-bound rerun |
| Commit / push / publication | NOT_RUN | Separate authorization required |

V2.1 leaves the public runtime synchronous. Its Broker probe is local compatibility evidence, not an application-traffic test or a production reliability, delay-SLA, throughput, or capacity claim.
