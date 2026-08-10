## ADDED Requirements

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

