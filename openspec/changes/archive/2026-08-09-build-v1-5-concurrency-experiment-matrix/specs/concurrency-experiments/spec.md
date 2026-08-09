## ADDED Requirements

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

## MODIFIED Requirements

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
