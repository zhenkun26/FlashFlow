## Purpose

Defines the synchronous single-unit ordering contract, including durable request idempotency, purchase eligibility, and the observable meaning of every order-creation result.

## ADDED Requirements

### Requirement: Create a single-unit order synchronously
The system SHALL accept an order request for exactly one user and one active activity SKU, and SHALL return a durable business result only after the corresponding database transaction commits.

#### Scenario: Eligible user creates an order
- **WHEN** an eligible user submits a valid request for an active activity SKU with available stock
- **THEN** the system returns a pending-payment order and the committed result includes its stable order identifier and expiration time

#### Scenario: Activity is not accepting orders
- **WHEN** a user submits an order request before the activity starts, after it ends, or while it is disabled
- **THEN** the system rejects the request without creating an order, claim, or inventory reservation

#### Scenario: Stock is unavailable
- **WHEN** a valid request reaches the source-of-truth inventory and no unit is available
- **THEN** the system returns a sold-out business result without creating an effective order or changing inventory

### Requirement: Idempotent order requests
The system SHALL scope each idempotency key to the ordering operation and caller identity, and repeated use of the same scoped key SHALL produce at most one business effect.

#### Scenario: Sequential duplicate request
- **WHEN** a completed order request is repeated with the same scoped idempotency key and equivalent payload
- **THEN** the system returns the previously committed business result without creating another order or changing inventory again

#### Scenario: Concurrent duplicate requests
- **WHEN** multiple equivalent requests with the same scoped idempotency key execute concurrently
- **THEN** all successful responses resolve to the same committed result and only one request changes business state

#### Scenario: Key reused with different payload
- **WHEN** a caller reuses a completed idempotency key with a different user or activity SKU payload
- **THEN** the system rejects the request as an idempotency conflict and preserves the original result

### Requirement: One effective order per user and activity SKU
The system SHALL permit at most one effective order for a user and activity SKU at any instant. Pending-payment and paid orders are effective; closed-unpaid orders are not effective.

#### Scenario: User already has an effective order
- **WHEN** the same user submits another request for an activity SKU while an earlier order is pending payment or paid
- **THEN** the system returns the existing-order business result and does not reserve additional inventory

#### Scenario: User retries after unpaid closure
- **WHEN** the user's previous order for the activity SKU is closed unpaid and its reservation has been released
- **THEN** the user may create a new order if the activity is active and inventory is available

#### Scenario: Different users compete for the same SKU
- **WHEN** different eligible users submit orders concurrently for the same activity SKU
- **THEN** each user is evaluated independently while the total effective orders remain bounded by available inventory

### Requirement: Ambiguous transport outcomes are recoverable
The system SHALL allow a caller to recover the committed result after a connection failure or response loss by retrying with the original idempotency key or querying the returned order identifier when known.

#### Scenario: Response is lost after commit
- **WHEN** an order transaction commits but the caller does not receive the HTTP response
- **THEN** retrying with the original scoped idempotency key returns the committed result without a second business effect

