# concurrency-experiments Specification

## Purpose

Defines reproducible database-concurrency experiments that demonstrate overselling failure modes, compare correct control strategies, and retain evidence without exposing unsafe behavior in normal runtime.

## Requirements

### Requirement: Unsafe read-then-write is isolated to the laboratory
The system SHALL make the unprotected read-then-write stock strategy available only in a dedicated test or lab environment and SHALL prevent normal runtime configuration from selecting it.

#### Scenario: Lab reproduces overselling
- **WHEN** coordinated concurrent requests execute the unsafe strategy against deliberately limited stock
- **THEN** the experiment can demonstrate a violated order or inventory invariant and labels the run as an expected unsafe result

#### Scenario: Normal runtime requests unsafe strategy
- **WHEN** normal runtime configuration attempts to select the unsafe strategy
- **THEN** application startup or configuration validation fails before serving order traffic

### Requirement: Correct MySQL strategies are comparable
The experiment suite SHALL exercise pessimistic locking, optimistic locking with bounded retries, and conditional atomic update against controlled workloads that vary one declared factor at a time while holding the schema, business invariants, dataset shape, and reporting contract constant.

#### Scenario: Strategy faces excess demand
- **WHEN** a correct strategy processes more distinct eligible users than available units
- **THEN** all committed results preserve non-negative inventory, stock conservation, and the effective-order limit

#### Scenario: Optimistic conflicts exceed retry budget
- **WHEN** a deterministic optimistic-lock experiment repeatedly loses version conflicts beyond its configured retry budget
- **THEN** it returns a retryable contention result, records retry exhaustion directly, and creates no partial order or inventory effect

#### Scenario: One-factor comparison executes
- **WHEN** the operator compares inventory strategy, request concurrency, connection-pool size, retry budget, stock level, or SKU contention shape
- **THEN** each comparison pair changes only the declared factor and records the resolved value of every controlled input

### Requirement: Safe ordering avoids foreign-key lock-upgrade cycles
Each safe ordering strategy SHALL establish exclusive control of the target stock row through its reservation operation before inserting order, claim, reservation, or movement rows that reference that stock, while preserving one atomic business transaction and bounded whole-transaction retry.

#### Scenario: Distinct users contend for one hot SKU
- **WHEN** concurrent eligible users order the same SKU through a safe strategy
- **THEN** no transaction first retains a foreign-key shared lock on the stock row and then waits to upgrade that same row for reservation

#### Scenario: Same-user claim wins during stock acquisition
- **WHEN** another transaction commits the user's effective claim after the current attempt's initial claim check
- **THEN** the current command returns or retries to the stable `EXISTING_EFFECTIVE_ORDER` result without a committed stock, order, claim, reservation, or movement effect

#### Scenario: Stock becomes unavailable during contention
- **WHEN** the reservation operation observes no available unit after waiting or retrying
- **THEN** the command rechecks the effective claim before returning `SOLD_OUT` so a concurrently committed same-user order retains result precedence

#### Scenario: Ordering protocol changes internally
- **WHEN** the stock-first protocol replaces the previous child-row-first sequence
- **THEN** the synchronous HTTP statuses, result codes, idempotent replay behavior, and bounded retry contract remain compatible

### Requirement: Concurrency interleavings are deterministic where correctness depends on order
The verification suite SHALL coordinate transaction boundaries explicitly for oversell, duplicate request, duplicate payment, payment-versus-expiration races, and foreign-key lock upgrades instead of relying only on timing or random load.

#### Scenario: Payment and expiration ordering is controlled
- **WHEN** a test pauses one transaction at a defined boundary and allows the competing transaction to proceed
- **THEN** the test proves the expected legal outcome for both possible commit orders

#### Scenario: Foreign-key lock-upgrade deadlock is reproduced
- **WHEN** two database transactions insert stock-referencing child rows before attempting to update the same stock row
- **THEN** a deterministic laboratory test records the shared-to-exclusive lock cycle and identifies the rolled-back victim as expected evidence

#### Scenario: Stock-first candidate is verified
- **WHEN** the same coordinated demand executes through the candidate stock-first protocol
- **THEN** the test completes without the reproduced lock-upgrade cycle and all committed ordering invariants remain valid

### Requirement: Every experiment records invariant evidence
Each comparison run SHALL report its resolved workload inputs, strategy, created and business-rejection counts by result code, technical-failure counts by cause, latency distribution, retry and lock-conflict counts, final inventory balances, effective-order count, invariant-check results, and verification status.

#### Scenario: Correct strategy run completes
- **WHEN** a correct-strategy experiment finishes
- **THEN** its report marks each invariant as passed only after querying committed database state and reconciles the total request count with all classified outcomes

#### Scenario: Verification cannot execute
- **WHEN** required infrastructure, tooling, or correctness-gate evidence is unavailable
- **THEN** the run is reported as blocked rather than passed and no unsupported performance claim is produced

#### Scenario: Invariant query fails
- **WHEN** the post-run committed-state query reports a violated invariant or cannot complete
- **THEN** the run is reported as failed or blocked and its request-rate and latency observations are not presented as successful capacity evidence

### Requirement: Characterization runs require an explicit correctness gate
The experiment suite SHALL execute or reference a current successful correctness gate before a characterization run can be reported as passed, and SHALL preserve `PASS`, `FAIL`, `BLOCKED`, and `NOT_RUN` as distinct evidence states.

#### Scenario: Correctness gate passes
- **WHEN** the required unit, migration, deterministic race, and safe-strategy invariant checks complete successfully for the tested revision
- **THEN** the characterization run may proceed and its report identifies the exact gate evidence

#### Scenario: Correctness gate fails or cannot run
- **WHEN** a required correctness check fails or its infrastructure is unavailable
- **THEN** no characterization result is labelled `PASS` and the report records `FAIL` or `BLOCKED` with the unmet prerequisite

### Requirement: Contention outcomes are classified by observable cause
The experiment suite SHALL distinguish committed creates, business rejections, bounded retry exhaustion, strategy conflicts, connection-pool acquisition failures, and unexpected technical failures without treating all expected HTTP statuses as equivalent success.

#### Scenario: Retry budget is exhausted
- **WHEN** a request exhausts its bounded whole-transaction retry budget
- **THEN** the experiment records a retry-exhaustion outcome and the number of attempts without recording a partial business effect

#### Scenario: Connection acquisition fails
- **WHEN** a request cannot acquire a database connection within the configured timeout
- **THEN** the experiment records connection-pool pressure separately from inventory conflict or sold-out rejection

#### Scenario: Business rejection occurs
- **WHEN** a request is rejected because the activity is inactive, inventory is sold out, or the user already has an effective order
- **THEN** the experiment records the specific committed business result separately from technical contention

### Requirement: Experiment evidence is reproducible and attributable
Every persisted experiment report SHALL identify the tested revision, workload manifest, runtime and database environment, start and end time, and a unique run identifier sufficient to reproduce or compare the run.

#### Scenario: Report is retained
- **WHEN** an experiment completes, fails, or is blocked
- **THEN** its evidence artifact records the resolved input configuration and result status without overwriting evidence from another run

#### Scenario: Runs are compared
- **WHEN** two experiment reports are presented as a controlled comparison
- **THEN** the comparison identifies the single intentionally changed factor and lists any uncontrolled environment differences

### Requirement: Redis admission is compared against the committed MySQL baseline
The experiment suite SHALL compare Redis admission with the stock-first MySQL-only baseline using controlled workloads that hold business data, MySQL strategy, concurrency, duration, pool, retry budgets, and SKU contention shape constant except for the declared admission mode.

#### Scenario: Admission comparison executes
- **WHEN** the operator runs the canonical MySQL-only and Redis-admission pair
- **THEN** both reports identify revision and resolved inputs and compare Redis decisions, MySQL transaction starts, HTTP outcomes, latency, retries, and committed-state invariants separately

#### Scenario: Redis reduces hot-row traffic
- **WHEN** excess demand is rejected by a trustworthy Redis decision
- **THEN** the report demonstrates the corresponding request did not start a MySQL ordering transaction without presenting the local result as production capacity

### Requirement: Redis failure and drift drills preserve source-of-truth invariants
The verification suite SHALL exercise Redis timeout or unavailability, lost or stale generations, ambiguous script replies, duplicate lifecycle operations, and Redis/MySQL drift through deterministic tests or controlled fault drills.

#### Scenario: Redis becomes unavailable during ordering
- **WHEN** the failure drill prevents a trustworthy admission decision
- **THEN** no new MySQL ordering attempt starts through automatic fallback and the technical outcome is classified distinctly

#### Scenario: Admission reply is lost
- **WHEN** the acquire script executes but the application observes a timeout
- **THEN** replay with the same idempotency identity proves that no additional token or durable business effect is created

#### Scenario: Lifecycle operation repeats
- **WHEN** token confirmation or release is delivered repeatedly
- **THEN** the experiment records one lifecycle effect and preserves non-negative bounded Redis availability

#### Scenario: Redis state disagrees with MySQL
- **WHEN** a controlled fixture creates excess, missing, orphaned, or stale admission state
- **THEN** reconciliation detects the declared discrepancy and all committed MySQL inventory and ordering invariants remain valid

### Requirement: V2 evidence classifies admission and convergence outcomes
Every Redis-enabled experiment SHALL record bounded counts for admission grants, no-token and duplicate-user decisions, not-ready and unavailable decisions, MySQL attempts avoided and started, token confirmations, safe releases, quarantined ambiguous outcomes, drift categories, reconciliation actions, and unexpected failures.

#### Scenario: Redis-enabled run completes
- **WHEN** a V2 characterization finishes
- **THEN** request totals and admission decisions reconcile, MySQL committed state passes all existing invariant queries, and the report distinguishes Redis observations from durable business outcomes

#### Scenario: Required Redis evidence is missing
- **WHEN** the workload completes but admission, lifecycle, or reconciliation evidence cannot be collected or reconciled
- **THEN** the report is failed or blocked and its latency or request rate is not presented as successful V2 evidence

### Requirement: Messaging evidence reconciles transport and business outcomes
The experiment suite SHALL record bounded command acceptance, definitive and ambiguous publication, delivery, redelivery, consumer attempt, acknowledgement, final command state, Redis lifecycle, and committed MySQL outcome counts without treating message throughput as order throughput.

#### Scenario: Duplicate-heavy run completes
- **WHEN** the command harness injects duplicate and concurrent deliveries
- **THEN** the report reconciles all delivery attempts to stable command identities and at most one committed business effect per identity

#### Scenario: Transport and database results diverge
- **WHEN** accepted or delivered command counts cannot be reconciled with completed, rejected, retryable, or unresolved command states and committed MySQL evidence
- **THEN** the report is `FAIL` or `BLOCKED` and does not present latency or request rate as successful V2.1 evidence

### Requirement: V3 readiness is revision-bound
The experiment suite SHALL declare V3 ready only for a specific revision whose synchronous compatibility tests, Redis failure tests, command-contract tests, RocketMQ compatibility spike, delayed-trigger races, and messaging fault matrix all pass.

#### Scenario: Every readiness gate passes
- **WHEN** all required V2.1 checks execute successfully against the same attributable revision
- **THEN** the readiness report is `PASS` and lists the separate evidence for each gate

#### Scenario: A gate is stale or not run
- **WHEN** any required gate belongs to another revision or is `FAIL`, `BLOCKED`, or `NOT_RUN`
- **THEN** V3 readiness is not asserted

### Requirement: V3 evidence reconciles acceptance through committed outcome
Every V3 experiment SHALL reconcile HTTP requests, prepared commands, acknowledged and ambiguous publications, deliveries and redeliveries, consumer attempts and acknowledgements, retries and dead letters, final command states, Redis lifecycle outcomes, and committed MySQL effects by stable command identity.

#### Scenario: V3 workload completes
- **WHEN** a controlled asynchronous workload and its bounded drain period complete
- **THEN** every request and transport attempt maps to a final completed, rejected, retryable, unresolved, or dead-lettered classification and all committed-state invariants pass

#### Scenario: Work remains in flight
- **WHEN** the observation bound ends with accepted, processing, ambiguous, or retryable commands that have no reconciled disposition
- **THEN** the run reports the unresolved counts and is not labelled a completed `PASS`

### Requirement: V3 characterization preserves the synchronous control
The experiment suite SHALL retain an explicit synchronous control and SHALL compare it with V3 only under declared, reproducible inputs without interpreting local message rate, acceptance rate, or completion rate as production capacity.

#### Scenario: Synchronous and V3 runs are compared
- **WHEN** the operator presents a controlled local comparison
- **THEN** the report separates acceptance latency from completion latency, lists every intentionally changed messaging input, and verifies the same final MySQL invariants

#### Scenario: Broker pressure causes technical outcomes
- **WHEN** broker, consumer, connection-pool, or database pressure produces retryable or unresolved work
- **THEN** each cause is classified separately from business rejection and no aggregate HTTP status is presented as successful throughput
