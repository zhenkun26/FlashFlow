# rocketmq-order-runtime Specification

## Purpose

Defines the live RocketMQ transport boundary for directly publishing, consuming, retrying, acknowledging, and inspecting asynchronous order and expiration messages without replacing MySQL business authority.

## Requirements

### Requirement: Live messaging is explicitly selected and guarded
The system SHALL load live RocketMQ producers, consumers, topics, and groups only in an explicit V3 messaging mode, SHALL fail startup for incomplete or contradictory live configuration, and SHALL keep the broker-free synchronous mode available.

#### Scenario: V3 mode starts with complete configuration
- **WHEN** the operator selects V3 messaging with reachable pinned broker endpoints and all required topic and group identities
- **THEN** the application starts the live producer and consumers and exposes the asynchronous order resources

#### Scenario: Live configuration is incomplete
- **WHEN** V3 messaging is selected without a required endpoint, topic, group, acknowledgement, retry, or delay setting
- **THEN** application startup fails before serving traffic

#### Scenario: Messaging remains disabled
- **WHEN** the operator selects the default broker-free mode
- **THEN** no RocketMQ client connects, the asynchronous order route is unavailable, and the synchronous order route retains its existing behavior

### Requirement: Direct publication has bounded honest outcomes
The system SHALL publish each order command with its stable command identity and versioned envelope, SHALL bound the publication attempt, and SHALL classify the observed result as broker acknowledged, definitely not published, or ambiguous without claiming an Outbox guarantee.

#### Scenario: Broker acknowledges the command
- **WHEN** the configured acknowledgement is received for the versioned command within the publication bound
- **THEN** the command becomes accepted and the asynchronous endpoint may return `202 Accepted`

#### Scenario: Publication definitely fails
- **WHEN** validation or the producer proves that no message reached the broker
- **THEN** the command is not reported as accepted and the caller receives a bounded retryable technical result

#### Scenario: Publication result is ambiguous
- **WHEN** the producer times out, loses the acknowledgement, or stops after the broker may have accepted the command
- **THEN** the command is not reported as accepted, the ambiguity is inspectable, and retry reuses the same command identity

#### Scenario: Process stops before direct publication
- **WHEN** the process stops after preparing the command but before broker acceptance can be proven
- **THEN** the system does not claim eventual publication and exposes the unresolved work for retry or reconciliation

### Requirement: Live consumption is at-least-once and recoverable
The system SHALL validate and consume order commands at least once through the existing idempotent ordering boundary and SHALL acknowledge a delivery only after its durable terminal result is recoverable or its input is deterministically non-retryable.

#### Scenario: Delivery commits a business result
- **WHEN** a valid command produces a committed MySQL order or rejection and the command ledger records the recoverable result
- **THEN** the consumer acknowledges the delivery and later duplicates recover the same result

#### Scenario: Consumer stops before commit
- **WHEN** handling stops before the ordering transaction commits
- **THEN** the delivery is not acknowledged, no partial business effect is visible, and redelivery may retry safely

#### Scenario: Acknowledgement is lost after commit
- **WHEN** MySQL and the command result commit but the broker acknowledgement is lost
- **THEN** redelivery recovers the durable result and creates no second order, claim, reservation, movement, or admission effect

### Requirement: Retries and dead letters are bounded and inspectable
The system SHALL apply declared bounded retry behavior to retryable transport or processing failures and SHALL route exhausted, poison, or unsupported messages to an inspectable dead-letter path without presenting dead-lettering as a business success.

#### Scenario: Retryable consumer failure recovers
- **WHEN** a transient failure occurs and a later attempt succeeds within the configured delivery bound
- **THEN** one durable command outcome is retained and every attempt is observable under the same stable identity

#### Scenario: Retry budget is exhausted
- **WHEN** retryable processing cannot complete within the declared delivery bound
- **THEN** the message reaches the configured dead-letter path, the command remains retryable or unresolved, and no partial business effect is reported

#### Scenario: Envelope is poison or unsupported
- **WHEN** an envelope is malformed, conflicts with its fingerprint, or uses an unsupported version
- **THEN** it is not executed as an order, its deterministic rejection is recorded, and its terminal transport disposition is inspectable without unbounded redelivery

### Requirement: Messaging observations avoid sensitive and unbounded identity
The system SHALL expose bounded metrics and retained evidence for publication, delivery, retry, acknowledgement, dead-letter, and unresolved outcomes while excluding raw user identifiers and idempotency keys from broker keys, metric tags, logs, and reports.

#### Scenario: Operator inspects a failed command
- **WHEN** a publication, consumption, or dead-letter outcome is inspected
- **THEN** the evidence uses the privacy-preserving command identity and bounded causes sufficient to correlate transport, ledger, Redis, and MySQL state

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
