## Purpose

Defines a Redis-backed admission contract that rejects excess hot-SKU demand before MySQL while preserving MySQL as the only authority for durable orders and inventory.

## ADDED Requirements

### Requirement: Admission decisions are atomic and scoped
The system SHALL make token availability, one-active-token-per-user-and-SKU, and admission creation one atomic operation scoped to a versioned activity SKU generation.

#### Scenario: Eligible user acquires a token
- **WHEN** an eligible request addresses a ready generation with an available token and no active token for that user
- **THEN** one token becomes held for a stable admission identifier and the available token count decreases by exactly one

#### Scenario: Distinct users exceed the token count
- **WHEN** concurrent distinct users request more admissions than the ready generation contains
- **THEN** no more than the initialized token count is held or confirmed and the remaining token count never becomes negative

#### Scenario: User already has an active token
- **WHEN** another idempotency identity for the same user and SKU requests admission while that user has a held or confirmed token
- **THEN** the system returns an already-admitted decision without consuming another token

### Requirement: Admission replay is idempotent
The system SHALL derive or persist a stable admission identity from the scoped ordering idempotency identity so an ambiguous client or Redis response can be retried without an additional admission effect.

#### Scenario: Same admission request repeats
- **WHEN** the same scoped idempotency identity repeats after its token was held
- **THEN** the system returns the same admission identity and token state without decrementing availability again

#### Scenario: Redis reply is lost after script execution
- **WHEN** an acquire operation executes but its response is not observed by the application
- **THEN** retrying the same scoped idempotency identity recovers the original decision rather than consuming a second token

### Requirement: Token lifecycle operations are idempotent
The system SHALL allow a held token to become confirmed after a committed effective order, a held token to be released after a definitely non-effective outcome, and a confirmed pending-order token to be released after committed unpaid closure; each transition SHALL apply at most once.

#### Scenario: MySQL order commits
- **WHEN** the source-of-truth transaction commits an effective order for a held admission
- **THEN** confirmation retains one consumed token and repeated confirmation does not change availability

#### Scenario: MySQL definitely creates no effective effect
- **WHEN** the ordering attempt is known to have no committed effective order and its held admission is safe to release
- **THEN** release returns exactly one token and repeated release returns the prior terminal result without increasing availability again

#### Scenario: Confirmed pending order closes unpaid
- **WHEN** MySQL atomically closes a pending order unpaid, releases its reservation, and removes its effective purchase claim
- **THEN** the corresponding confirmed admission is released exactly once after commit so the capacity and user may be admitted again

#### Scenario: Paid order remains effective
- **WHEN** MySQL confirms payment and converts reserved inventory to sold
- **THEN** the confirmed admission remains consumed and repeated payment handling does not alter Redis capacity

#### Scenario: MySQL reports sold out after Redis admission
- **WHEN** a held admission reaches MySQL but committed MySQL inventory has no available unit
- **THEN** the system records drift requiring reconciliation and does not blindly increase Redis availability

#### Scenario: MySQL outcome is ambiguous
- **WHEN** the application cannot prove whether the source-of-truth transaction committed
- **THEN** it does not release the token until committed MySQL evidence resolves the outcome

### Requirement: Untrustworthy Redis state fails closed
The system SHALL not automatically bypass admission to the MySQL hot path when Redis is unavailable, times out, has an unknown script version, or lacks a ready generation.

#### Scenario: Redis is unreachable before admission
- **WHEN** an order request cannot obtain a trustworthy admission decision within the configured bound
- **THEN** the request returns a retryable technical result and does not begin the MySQL ordering transaction

#### Scenario: Admission state was lost or evicted
- **WHEN** required keys or their generation metadata are missing or inconsistent
- **THEN** the SKU becomes not ready and no request is admitted until authoritative reinitialization completes

#### Scenario: Explicit MySQL-only experiment mode
- **WHEN** a test or lab configuration deliberately selects the existing MySQL-only path
- **THEN** admission is bypassed visibly for that controlled run without enabling automatic runtime fallback

### Requirement: Redis cannot create a durable business success
The system SHALL return a created or existing-effective-order success only from committed MySQL state; a Redis admission decision alone SHALL confer no durable order, claim, reservation, or inventory right.

#### Scenario: Admission succeeds and MySQL rejects
- **WHEN** Redis grants admission but the MySQL transaction returns a committed rejection
- **THEN** the public result reflects MySQL and no Redis state is presented as an order success

#### Scenario: Redis confirmation fails after MySQL commit
- **WHEN** MySQL commits an effective order but Redis confirmation is unavailable or ambiguous
- **THEN** the committed MySQL result remains recoverable through the original idempotency key and the Redis discrepancy is exposed for reconciliation
