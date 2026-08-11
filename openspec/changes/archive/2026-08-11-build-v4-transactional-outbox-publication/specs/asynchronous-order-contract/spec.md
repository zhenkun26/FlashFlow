## ADDED Requirements

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
