## Purpose

Defines reproducible compatibility and failure evidence that must pass before RocketMQ becomes part of FlashFlow's asynchronous V3 ordering path.

## ADDED Requirements

### Requirement: RocketMQ compatibility is proven against pinned inputs
The readiness suite SHALL identify pinned client, broker, name-server, image, protocol, persistence, acknowledgement, retry, and delayed-delivery settings and SHALL retain attributable results for every tested capability.

#### Scenario: Compatibility spike passes
- **WHEN** the pinned local topology publishes, consumes, redelivers, and delays versioned test commands as declared
- **THEN** the report records the exact resolved inputs and marks each verified capability `PASS`

#### Scenario: Required behavior is unsupported
- **WHEN** the pinned topology cannot provide a required acknowledgement, delay, retry, or inspection behavior
- **THEN** the report is `FAIL` or `BLOCKED` and V3 implementation is not declared ready

### Requirement: Failure drills exercise every message boundary
The readiness suite SHALL deterministically exercise definitive publish failure, ambiguous publish response, duplicate delivery, consumer interruption before and after MySQL commit, acknowledgement loss, broker restart, delayed redelivery, and poison-envelope handling.

#### Scenario: Publish outcome is ambiguous
- **WHEN** a drill loses the producer response after the broker may have accepted a command
- **THEN** retry uses the same command identity, admission is not unsafely returned, and duplicate downstream delivery remains harmless

#### Scenario: Consumer restarts around commit
- **WHEN** a drill interrupts command handling immediately before or after MySQL commit
- **THEN** redelivery creates no partial or duplicate business effect and the final command result is recoverable

#### Scenario: Delayed trigger repeats
- **WHEN** the same expiration trigger is delivered more than once or after the fallback scanner already closed the order
- **THEN** the order and inventory state change at most once and all later triggers observe the terminal state

### Requirement: Readiness evidence has an explicit gate
The readiness suite SHALL reconcile produced, acknowledged, ambiguous, delivered, redelivered, consumed, rejected, unresolved, and final MySQL outcome counts and SHALL keep local messaging observations separate from production reliability claims.

#### Scenario: Counts and committed state reconcile
- **WHEN** a readiness run completes with every required correctness and failure drill passing
- **THEN** it may be labelled `PASS` and the report identifies the tested revision and final MySQL invariants

#### Scenario: Evidence is incomplete
- **WHEN** any required count, broker input, failure outcome, or committed-state query is missing or inconsistent
- **THEN** the run is `FAIL` or `BLOCKED` and cannot authorize V3 implementation or a production capacity claim
