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

### Requirement: V3 exposes asynchronous acceptance and status resources
The system SHALL expose asynchronous ordering only when V3 messaging is enabled, SHALL return `202 Accepted` only after trustworthy broker acknowledgement, and SHALL expose a stable command-status resource that never presents transport acceptance as an order success.

#### Scenario: New command is accepted by the broker
- **WHEN** an eligible request acquires admission and the broker acknowledges its stable versioned command
- **THEN** the endpoint returns `202 Accepted` with the command identifier and status location but no created-order claim

#### Scenario: Accepted command completes
- **WHEN** consumption commits a durable MySQL result and records it in the command ledger
- **THEN** the status resource reports the corresponding completed or rejected result and includes an order identifier only when committed MySQL evidence provides one

#### Scenario: Publication is not acknowledged
- **WHEN** publication definitely fails or remains ambiguous within the configured bound
- **THEN** the endpoint does not return `202 Accepted`, returns a bounded retryable response, and preserves the original command identity for retry and inspection

#### Scenario: Unknown command is queried
- **WHEN** a caller queries a command identity that is not durably known within that caller's scope
- **THEN** the status resource returns not found without exposing another caller's command state

### Requirement: Synchronous and asynchronous paths converge safely
The system SHALL preserve the existing synchronous endpoint and SHALL make synchronous execution, asynchronous retry, duplicate delivery, and command-status lookup converge on the same scoped idempotency identity and committed MySQL effect.

#### Scenario: Synchronous order wins before command consumption
- **WHEN** the synchronous path commits the effective order before an equivalent accepted command is consumed
- **THEN** command consumption recovers the existing durable result and creates no duplicate effect

#### Scenario: Asynchronous command wins before synchronous retry
- **WHEN** the consumer commits the effective order before an equivalent synchronous retry executes
- **THEN** the synchronous retry recovers the same committed result without another admission or ordering effect

### Requirement: V4 Outbox acceptance is durable before broker delivery
In `OUTBOX` mode the system SHALL return `202 Accepted` only after one stable command and its immutable publish-ready Outbox record commit atomically in MySQL, SHALL NOT require inline RocketMQ acknowledgement for that response, and SHALL keep acceptance distinct from committed order completion.

#### Scenario: Durable command awaits dispatch
- **WHEN** command and Outbox persistence commits but RocketMQ has not yet acknowledged publication
- **THEN** the endpoint returns `202 Accepted` with the stable command identifier and caller-scoped status location but no created-order claim

#### Scenario: Durable acceptance cannot commit
- **WHEN** the command-plus-Outbox transaction fails or its commit outcome cannot be proven
- **THEN** the endpoint does not return `202 Accepted` and a retry uses the same scoped identity

#### Scenario: Accepted command is queried before delivery
- **WHEN** a caller queries a durably accepted command that is ready, claimed, or retryable for publication
- **THEN** the status resource reports a bounded accepted or processing view without exposing transport internals or claiming order completion

#### Scenario: Accepted command later completes
- **WHEN** at-least-once publication and consumption produce a committed MySQL business result
- **THEN** the status resource reports the durable completed or rejected result and includes an order identifier only when committed MySQL evidence provides one

### Requirement: Acceptance semantics are mode-specific and explicit
The system SHALL make the selected asynchronous reliability mode observable and SHALL NOT mix the V3 inline broker-acknowledged acceptance contract with the V4 durable Outbox acceptance contract within one request path.

#### Scenario: Direct mode handles a request
- **WHEN** the operator explicitly selects `DIRECT`
- **THEN** the existing V3 route returns `202 Accepted` only after trustworthy inline Broker acknowledgement

#### Scenario: Outbox mode handles a request
- **WHEN** the operator explicitly selects `OUTBOX`
- **THEN** the route applies the atomic durable acceptance contract and leaves publication to recoverable dispatch
