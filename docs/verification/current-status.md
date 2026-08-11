# Current verification status

Status vocabulary:

- `PASS`: command executed and assertions passed.
- `FAIL`: command executed and failed.
- `BLOCKED`: a required runtime, clean revision, authorization, or other release prerequisite was unavailable or unreconciled.
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
| RocketMQ 5.3.4 compatibility | PASS | Revision-bound report `reports/messaging/20260809T095421Z-rocketmq-spike` recorded registration, `SEND_OK`, matching consumption, and `SYNC_FLUSH` |
| OpenSpec strict validation | PASS | 9/9 change-plus-main items passed before archive; 8/8 main specifications passed after archive |
| Clean revision / V3 readiness | PASS | All required implementation gates were rerun after commit `178b602`; see [V2.1 local report](2026-08-09-v2-1-local.md) |
| OpenSpec sync/archive | PASS | Six deltas synchronized into main specs and the completed change was reviewed for archival |
| Commit / push / publication | PASS | V2.1 implementation `178b602` and archive `8a36c85` were pushed to `origin/main` |

V2.1 leaves the public runtime synchronous. Its Broker probe is local compatibility evidence, not an application-traffic test or a production reliability, delay-SLA, throughput, or capacity claim.

## Current V3 apply-workflow snapshot (2026-08-10)

| Gate | Status | Evidence |
|---|---|---|
| Complete Maven/Testcontainers suite | PASS | 100 tests, 0 failures, 0 errors, 0 skipped |
| Real RocketMQ application and fault matrix | PASS | Revision-bound report `reports/messaging/20260810T122601Z-v3-live`: 10 live tests passed |
| Deterministic messaging and reconciliation matrix | PASS | Final report: 18 selected tests passed |
| Controlled local synchronous/V3 comparison | PASS | Acceptance/completion were recorded separately with scope `LOCAL_SINGLE_REQUEST_NOT_CAPACITY`; see [V3 local report](2026-08-10-v3-local.md) |
| Strict OpenSpec / Compose / source boundary / diff | PASS | Strict change validation, Compose resolution, script syntax, six focused boundary tests, and `git diff --check` passed |
| Clean attributable V3 revision | PASS | Report records implementation revision `1e66e1b1ebaaccae82d596d493859028434d11c8`, `dirtyWorktree=false`, and `status=PASS`; the same tracked revision passed the 100-test and static gates |
| OpenSpec sync/archive | PASS | Six deltas synchronized; archived as `2026-08-10-build-v3-live-rocketmq-ordering` with 53/53 tasks; post-archive strict validation passed 9/9 main specs |
| Commit / push / publication | PASS | V3 implementation, revision-bound evidence, and OpenSpec sync/archive were pushed to `origin/main` through `69918c9` |

V3 implementation, clean-revision gates, specification synchronization, archival, and publication are complete. The live report is evidence for a disposable laboratory topology only; it is not a production availability, persistence, delay-SLA, throughput, or capacity claim. Direct publication retains the documented V4 Outbox/CDC boundary.

## Current V4 apply-workflow snapshot (2026-08-11)

| Gate | Status | Evidence |
|---|---|---|
| OpenSpec planning and strict validation | PASS | Proposal, five deltas, design, and tasks exist; `openspec validate build-v4-transactional-outbox-publication --strict` passed in the retained gate run |
| Java and test compilation | PASS | Java 21 release target compiled 93 production source files and 54 test source files on OpenJDK 26.0.2 |
| Flyway/MySQL 8.4.6 atomicity and constraints | PASS | 14 selected acceptance, persistence, and reconciliation tests passed against Testcontainers MySQL and Redis |
| Real RocketMQ V4 recovery matrix | PASS | 5 live tests cover normal publication, Broker outage, producer ACK loss, consumer ACK loss/redelivery, concurrent dispatchers, and bounded backlog drain |
| Deterministic messaging and evidence matrix | PASS | 30 selected boundary, configuration, dispatcher, consumer, metrics, manifest, and evidence tests passed |
| Bounded backlog evidence | PASS | 12 durable commands drained with all command, Outbox, order, claim, reservation, movement, and admission invariants passing; local latency, attempts, retries, duplicates, and final balances are retained |
| Complete suite and static gates | PASS | 123 tests passed with no failures, strict OpenSpec validation passed, and `git diff --check` passed |
| Revision-bound local report | PASS | `reports/messaging/20260811T013206Z-v4-outbox` records revision `292b47d`, dirty state, environment, 368-second duration, fault schedule, counts, latency dimensions, and invariant results |
| Clean attributable V4 revision | BLOCKED | The successful report correctly records `dirtyWorktree=true`, `gateStatus=PASS`, and `releaseStatus=BLOCKED`; a commit and clean-revision rerun require authorization |
| OpenSpec sync/archive | NOT_RUN | Requires completed tasks, gates, review, and separate authorization |
| Commit / push / publication | NOT_RUN | Requires separate authorization after clean revision-bound review |

The V4 technical gates pass, but release remains blocked until the same implementation is committed and rerun as one clean attributable revision. The retained local report is evidence for a disposable topology only; it does not establish exactly-once publication, CDC behavior, production availability, persistence, failover, throughput, capacity, or latency SLA.
