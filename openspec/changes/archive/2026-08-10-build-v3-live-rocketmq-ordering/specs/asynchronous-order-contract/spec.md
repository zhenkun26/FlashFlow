## ADDED Requirements

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

