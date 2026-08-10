# messaging-readiness-experiments Specification

## Purpose

Defines reproducible compatibility and failure evidence that must pass before RocketMQ becomes part of FlashFlow's asynchronous V3 ordering path.

## Requirements

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

### Requirement: Live application messaging has revision-bound fault evidence
The verification suite SHALL exercise the actual V3 HTTP producer, RocketMQ consumers, command ledger, Redis admission lifecycle, ordering transaction, delayed trigger, and scanner against pinned resolved inputs for one attributable revision.

#### Scenario: Live end-to-end gate passes
- **WHEN** accepted commands and delayed triggers traverse the real application and pinned broker topology and every required invariant reconciles
- **THEN** the report records the revision, resolved broker and client inputs, application configuration, separate transport and business counts, and `PASS`

#### Scenario: Required component is simulated or stale
- **WHEN** a required V3 gate uses only the transport-neutral harness, belongs to another revision, or omits a live boundary
- **THEN** V3 implementation evidence remains `NOT_RUN`, `FAIL`, or `BLOCKED` rather than `PASS`

### Requirement: Live failure drills cover transport and process boundaries
The verification suite SHALL exercise broker unavailability, definitive and ambiguous producer outcomes, duplicate delivery, process interruption before and after MySQL commit, acknowledgement loss, retry exhaustion, dead-letter handling, delayed-message loss, and scanner recovery.

#### Scenario: Producer acknowledgement is lost
- **WHEN** a drill allows possible broker acceptance but hides the producer acknowledgement
- **THEN** the endpoint does not claim acceptance, admission is not unsafely returned, and retry under the stable command identity creates at most one business effect

#### Scenario: Consumer restarts after commit
- **WHEN** a drill stops the consumer after MySQL completion but before delivery acknowledgement
- **THEN** redelivery recovers one durable result and the report reconciles the duplicate attempt without another business effect

#### Scenario: Message reaches the dead-letter path
- **WHEN** a declared poison or retry-exhaustion fixture is processed
- **THEN** its dead-letter evidence is inspectable, no unsupported business success is reported, and any retained admission is reconciled explicitly

#### Scenario: Delayed trigger is unavailable
- **WHEN** delayed publication or delivery is prevented for an expiring order
- **THEN** the scanner closes the eligible order within its declared local recovery bound and the report distinguishes trigger loss from committed closure
