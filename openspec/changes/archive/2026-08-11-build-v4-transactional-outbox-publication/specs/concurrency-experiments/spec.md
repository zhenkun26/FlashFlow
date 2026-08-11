## ADDED Requirements

### Requirement: V4 evidence reconciles durable acceptance through publication and business outcome
Every V4 experiment SHALL reconcile HTTP attempts, atomic command and Outbox acceptance, dispatch claims and expirations, publication attempts and acknowledgements, deliveries and redeliveries, final command and Outbox dispositions, Redis lifecycle outcomes, and committed MySQL effects by stable command identity.

#### Scenario: V4 workload and drain complete
- **WHEN** a controlled workload and its declared dispatch and consumption drain periods complete
- **THEN** every durably accepted identity maps to an acknowledged, retryable, exhausted, or explicitly unresolved Outbox disposition and every committed business effect satisfies existing invariants

#### Scenario: Durable accepted work is unexplained
- **WHEN** the observation bound ends with an accepted command missing attributable Outbox, publication, or terminal business evidence
- **THEN** the report identifies the unresolved identities and is not labelled a completed `PASS`

#### Scenario: Claim expires during a fault drill
- **WHEN** a dispatcher is interrupted after claiming work
- **THEN** evidence identifies the original claim, bounded expiry, takeover, later publication disposition, and final business result without counting a duplicate effect

### Requirement: V3 and V4 are compared under declared equivalent inputs
The experiment suite SHALL retain V3 `DIRECT` as a control for V4 `OUTBOX`, SHALL declare every changed acceptance and dispatch input, and SHALL compare reliability and latency dimensions without presenting local results as production capacity, availability, or an SLA.

#### Scenario: Direct and Outbox runs are compared
- **WHEN** the operator presents a controlled V3/V4 comparison
- **THEN** the report separates HTTP acceptance latency, time-to-broker-acknowledgement, completion latency, recovery behavior, duplicate publication, backlog drain, and final invariant results

#### Scenario: Broker outage spans acceptance
- **WHEN** equivalent requests are exercised while RocketMQ is unavailable
- **THEN** the report distinguishes V3 non-acceptance from V4 durable acceptance and verifies that V4 publishes automatically after recovery

#### Scenario: Local comparison completes
- **WHEN** every controlled run reaches an attributable disposition
- **THEN** the report states its machine, container, dataset, revision, duration, concurrency, fault schedule, and local-only evidence boundary
