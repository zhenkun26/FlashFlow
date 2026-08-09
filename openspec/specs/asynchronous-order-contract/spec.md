# asynchronous-order-contract Specification

## Purpose

Defines the transport-neutral command and result contract that lets a later asynchronous ordering transport preserve durable idempotency and MySQL-authoritative business outcomes.

## Requirements

### Requirement: Asynchronous order commands have stable versioned identity
The system SHALL represent each asynchronous order request with a stable command identifier derived from the scoped ordering idempotency identity, a schema version, caller, activity SKU, payload fingerprint, creation time, and trace correlation, and SHALL reject an unsupported version or conflicting reuse before any business effect.

#### Scenario: Equivalent request is redelivered
- **WHEN** equivalent envelopes for the same scoped idempotency identity are delivered repeatedly or concurrently
- **THEN** they resolve to one command identity and at most one durable ordering effect

#### Scenario: Identity is reused with another payload
- **WHEN** an envelope reuses a command or idempotency identity with a different payload fingerprint
- **THEN** the system records a deterministic conflict and does not execute the conflicting payload

#### Scenario: Envelope version is unsupported
- **WHEN** a consumer receives an envelope whose schema version it cannot interpret
- **THEN** it rejects the envelope with a bounded non-business outcome and creates no order or inventory effect

### Requirement: Acceptance is distinct from order completion
The asynchronous contract SHALL distinguish broker-acknowledged acceptance from command consumption and committed MySQL completion, and SHALL expose a bounded query result without presenting acceptance as an order success.

#### Scenario: Broker acknowledges a command
- **WHEN** the future asynchronous endpoint receives a trustworthy broker acknowledgement
- **THEN** it may return `202 Accepted` with a stable command identifier and status location but does not return a created or existing-order success

#### Scenario: Publication is not acknowledged
- **WHEN** publication definitely fails before broker acceptance or its outcome is ambiguous
- **THEN** the endpoint does not claim acceptance and returns a bounded retryable result using the original idempotency identity

#### Scenario: Command status is queried
- **WHEN** a caller queries a known command identity
- **THEN** the system returns one of the bounded acceptance, processing, completed, rejected, retryable, or unresolved states and includes an order identifier only when committed MySQL evidence provides one

### Requirement: Command consumption is idempotent and MySQL-authoritative
The system SHALL execute an accepted command through a transport-neutral consumer boundary that reuses the existing ordering idempotency, purchase-claim, and inventory invariants, and SHALL acknowledge successful consumption only after the resulting durable state is recoverable.

#### Scenario: Duplicate deliveries reach the consumer
- **WHEN** the same command is delivered more than once sequentially or concurrently
- **THEN** every delivery converges on the same durable result and only one delivery can create the order, claim, reservation, movement, and idempotency completion

#### Scenario: Consumer stops before commit
- **WHEN** command handling stops before the MySQL transaction commits
- **THEN** no partial ordering effect is visible and a redelivery can retry the command safely

#### Scenario: Consumer loses acknowledgement after commit
- **WHEN** MySQL commits but the consumer acknowledgement is lost
- **THEN** redelivery recovers the committed result and produces no second business effect

### Requirement: V2.1 keeps live ordering synchronous
The system SHALL keep the existing synchronous order endpoint and normal runtime path compatible throughout V2.1 and SHALL not enable a broker-dependent endpoint or consumer until a separate V3 change is applied.

#### Scenario: V2.1 serves an order request
- **WHEN** a client uses the existing order endpoint under V2.1
- **THEN** the request retains its current synchronous status, response, Redis admission, and committed-MySQL semantics without requiring RocketMQ
