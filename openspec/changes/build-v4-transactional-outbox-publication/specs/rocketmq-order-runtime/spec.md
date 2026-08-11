## ADDED Requirements

### Requirement: V4 Outbox messaging is explicitly selected and guarded
The system SHALL activate Outbox acceptance and dispatch only in explicit `OUTBOX` mode, SHALL retain explicit `DIRECT` mode as the V3 comparison control, SHALL retain `DISABLED` as broker-free behavior, and SHALL fail startup when the selected mode lacks required or internally consistent settings.

#### Scenario: Outbox mode starts with complete configuration
- **WHEN** the operator selects `OUTBOX` with valid database, RocketMQ, routing, dispatcher, lease, batch, retry, and acknowledgement settings
- **THEN** the application exposes durable asynchronous acceptance and starts recoverable Outbox dispatch

#### Scenario: Outbox configuration is incomplete
- **WHEN** `OUTBOX` is selected with missing, contradictory, unsafe, or shared routing or ownership settings
- **THEN** startup fails before accepting asynchronous traffic or claiming Outbox records

#### Scenario: Direct comparison mode is selected
- **WHEN** the operator selects `DIRECT`
- **THEN** the V3 bounded direct producer path remains active without polling Outbox records

#### Scenario: Messaging is disabled
- **WHEN** the operator selects `DISABLED`
- **THEN** no RocketMQ client or Outbox dispatcher connects and synchronous ordering remains available

### Requirement: Outbox dispatch preserves the existing consumer contract
The system SHALL publish the existing stable versioned order-command envelope and routing identity so direct and Outbox publications converge through the same at-least-once consumer, command ledger, and MySQL business invariants.

#### Scenario: Direct and Outbox delivery carry equivalent commands
- **WHEN** equivalent commands are published by the two explicit modes in separate controlled runs
- **THEN** consumers validate the same schema and identity rules and converge on the same durable business semantics

#### Scenario: Outbox publication is duplicated
- **WHEN** claim expiry or lost acknowledgement causes the same envelope to be published more than once
- **THEN** existing consumer recovery creates at most one order, claim, reservation, movement, and terminal command result
