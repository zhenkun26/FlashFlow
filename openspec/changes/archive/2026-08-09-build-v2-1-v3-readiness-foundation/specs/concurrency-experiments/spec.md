## ADDED Requirements

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
