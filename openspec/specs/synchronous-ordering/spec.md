# synchronous-ordering Specification

## Purpose

Defines the synchronous single-unit ordering contract, including durable request idempotency, purchase eligibility, and the observable meaning of every order-creation result.

## Requirements

### Requirement: Create a single-unit order synchronously
The system SHALL accept an order request for exactly one user and one active activity SKU, SHALL require a trustworthy Redis admission in V2 before starting a new MySQL business attempt, and SHALL return a durable business result only after the corresponding MySQL transaction commits.

#### Scenario: Eligible user creates an order
- **WHEN** an eligible user receives admission and submits a valid request for an active activity SKU with committed MySQL stock
- **THEN** the system returns a pending-payment order and the committed result includes its stable order identifier and expiration time

#### Scenario: Activity is not accepting orders
- **WHEN** a user submits an order request before the activity starts, after it ends, or while it is disabled
- **THEN** the system rejects the request without creating an order, claim, or inventory reservation and safely resolves any held admission

#### Scenario: Redis defers excess demand
- **WHEN** a new request receives a trustworthy no-token admission decision
- **THEN** the system returns a retryable admission result without asserting durable sold-out state and without starting the MySQL ordering transaction

#### Scenario: Redis cannot decide safely
- **WHEN** Redis admission is unavailable, ambiguous, stale, or not ready
- **THEN** the system returns a retryable technical result without automatically falling through to MySQL

#### Scenario: MySQL rejects a Redis-admitted request
- **WHEN** a valid admitted request reaches the source-of-truth inventory but MySQL cannot commit an effective order
- **THEN** the system returns the committed MySQL result, creates no effective order beyond MySQL constraints, and resolves or quarantines the held token according to the proven outcome

### Requirement: Idempotent order requests
The system SHALL scope each idempotency key to the ordering operation and caller identity, SHALL reuse that identity across Redis admission and MySQL ordering, and repeated use of the same scoped key SHALL produce at most one Redis admission effect and at most one durable business effect.

#### Scenario: Sequential duplicate request
- **WHEN** a completed order request is repeated with the same scoped idempotency key and equivalent payload
- **THEN** the system returns the previously committed MySQL business result without consuming another admission token, creating another order, or changing inventory again

#### Scenario: Concurrent duplicate requests
- **WHEN** multiple equivalent requests with the same scoped idempotency key execute concurrently
- **THEN** they converge on one admission identity and one committed result and only one request changes durable business state

#### Scenario: Key reused with different payload
- **WHEN** a caller reuses an idempotency key with a different user or activity SKU payload
- **THEN** the system rejects the request as an idempotency conflict and preserves the original Redis admission and MySQL result

#### Scenario: Retry follows an ambiguous admission response
- **WHEN** a caller retries the same request after the Redis script may have executed but no response was received
- **THEN** the system recovers the same admission state before proceeding and does not decrement another token

### Requirement: One effective order per user and activity SKU
The system SHALL permit at most one effective MySQL order and at most one active Redis admission token for a user and activity SKU at any instant. Pending-payment and paid orders are effective; closed-unpaid orders are not effective.

#### Scenario: User already has an effective order
- **WHEN** the same user submits another request for an activity SKU while an earlier order is pending payment or paid
- **THEN** the system returns the existing-order business result and does not reserve additional Redis or MySQL inventory

#### Scenario: User retries after unpaid closure
- **WHEN** the user's previous order is closed unpaid, its MySQL reservation is released, and Redis admission state has been released or reconciled
- **THEN** the user may acquire a new admission and create a new order if the activity is active and inventory is available

#### Scenario: Different users compete for the same SKU
- **WHEN** different eligible users submit orders concurrently for the same activity SKU
- **THEN** each user is evaluated independently while both Redis admissions and committed effective orders remain within their authoritative bounds

#### Scenario: Redis user state drifts from MySQL
- **WHEN** Redis would allow or deny a user inconsistently with an effective MySQL claim
- **THEN** MySQL preserves the one-effective-order invariant and the discrepancy becomes reconciliation evidence rather than a second business effect

### Requirement: Ambiguous transport outcomes are recoverable
The system SHALL allow a caller to recover the committed result after a connection failure or response loss by retrying with the original idempotency key or querying the returned order identifier when known.

#### Scenario: Response is lost after commit
- **WHEN** an order transaction commits but the caller does not receive the HTTP response
- **THEN** retrying with the original scoped idempotency key returns the committed result without a second business effect

### Requirement: Synchronous and asynchronous execution converge
Any transport-neutral asynchronous command harness SHALL invoke the same durable ordering boundary as the synchronous endpoint and SHALL preserve its request fingerprint, result precedence, bounded whole-transaction retries, purchase-claim uniqueness, and inventory invariants.

#### Scenario: Both paths receive the same business request
- **WHEN** the synchronous endpoint and the asynchronous harness execute equivalent scoped requests under the same committed starting state
- **THEN** they expose compatible final business results and neither path can bypass MySQL validation

#### Scenario: Paths race for one identity
- **WHEN** synchronous and asynchronous execution race with the same scoped idempotency identity
- **THEN** they converge on one completed idempotency record and at most one effective order
