# transactional-outbox-publication Specification

## Purpose

Defines how durably accepted order commands are atomically recorded, leased, published to RocketMQ at least once, recovered after failure, and reconciled without changing MySQL business authority.

## Requirements

### Requirement: Accepted commands are atomically publishable
The system SHALL commit the stable command identity, immutable versioned publication envelope, and publish-ready Outbox record in one MySQL transaction before reporting V4 acceptance, and SHALL expose no partial durable acceptance when that transaction fails.

#### Scenario: Command and Outbox commit
- **WHEN** an eligible request acquires admission and the acceptance transaction commits
- **THEN** exactly one command identity and one matching publish-ready Outbox record are durably visible and the endpoint may return `202 Accepted`

#### Scenario: Acceptance transaction rolls back
- **WHEN** command or Outbox persistence fails before the acceptance transaction commits
- **THEN** neither partial record is reported as accepted and the caller receives a bounded retryable result under the same identity

#### Scenario: Response is lost after commit
- **WHEN** the acceptance transaction commits but the process stops or the HTTP response is lost
- **THEN** a same-identity retry recovers the durable acceptance without creating another command, Outbox record, or admission effect

### Requirement: Dispatch uses bounded recoverable ownership
The system SHALL dispatch publish-ready Outbox records in bounded batches under expiring ownership, SHALL prevent concurrent active owners from publishing the same claim, and SHALL permit takeover after ownership expires.

#### Scenario: Competing dispatchers claim work
- **WHEN** multiple dispatcher instances select the same eligible Outbox record concurrently
- **THEN** at most one holds the active claim while the others leave that claim unchanged

#### Scenario: Dispatcher stops after claim
- **WHEN** a dispatcher stops after claiming a record but before recording a publish outcome
- **THEN** the record becomes eligible for takeover after the declared ownership bound without operator mutation

#### Scenario: No work is eligible
- **WHEN** no ready or retry-due record is available
- **THEN** the dispatcher performs no broker publication and does not mutate acknowledged, exhausted, or actively claimed records

### Requirement: Publication is recoverable and at least once
The system SHALL retain every durably accepted command until RocketMQ acknowledgement is recorded or a bounded non-success disposition is inspectable, and SHALL tolerate duplicate publication without creating a duplicate business effect.

#### Scenario: Broker acknowledges publication
- **WHEN** the dispatcher receives the configured trustworthy acknowledgement for the stable versioned envelope
- **THEN** it records the Outbox item as acknowledged and later dispatch cycles do not intentionally republish it

#### Scenario: Broker is temporarily unavailable
- **WHEN** a publication attempt fails without acknowledgement and remains within its retry policy
- **THEN** the Outbox item remains durable, receives a bounded retry schedule, and is attempted again without requiring a caller retry

#### Scenario: Acknowledgement is lost
- **WHEN** RocketMQ may have accepted the message but the dispatcher cannot durably prove acknowledgement
- **THEN** the item remains recoverable and may be published again under the same command identity

#### Scenario: Dispatcher stops after acknowledgement
- **WHEN** RocketMQ accepts the message but the dispatcher stops before recording acknowledgement
- **THEN** expired-claim recovery may republish the same identity and consumer idempotency permits at most one durable ordering effect

### Requirement: Exhausted and corrupt work is bounded and inspectable
The system SHALL stop automatic publication after the declared attempt or validity bound, SHALL preserve the immutable accepted envelope and bounded failure evidence, and SHALL NOT present exhaustion as order completion or automatically release admission without authoritative proof.

#### Scenario: Retry budget is exhausted
- **WHEN** a valid Outbox item cannot be acknowledged within the configured publication attempt bound
- **THEN** it receives an inspectable exhausted disposition, remains correlated to the command and admission identity, and is excluded from automatic dispatch

#### Scenario: Stored envelope cannot be validated
- **WHEN** an Outbox item has an unsupported version, fingerprint mismatch, or invalid bounded routing metadata
- **THEN** no order command is published from that item and its deterministic invalid disposition is retained for operator inspection

### Requirement: Outbox operations expose bounded attributable evidence
The system SHALL expose low-cardinality metrics and append-only evidence for ready, claimed, acknowledged, retryable, expired-claim, exhausted, and unresolved Outbox outcomes while excluding raw user identifiers, idempotency keys, full payloads, and unbounded exception text.

#### Scenario: Operator inspects a backlog
- **WHEN** ready or retryable work accumulates
- **THEN** the operator can observe bounded backlog count, oldest eligible age, claim recovery, attempt, and disposition evidence correlated by privacy-preserving command identity

#### Scenario: Evidence cannot reconcile accepted work
- **WHEN** an accepted command has neither a matching Outbox disposition nor a committed MySQL business result
- **THEN** verification reports unresolved work and does not label the run complete or passed
