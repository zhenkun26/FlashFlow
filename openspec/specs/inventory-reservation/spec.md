# inventory-reservation Specification

## Purpose

Defines the source-of-truth inventory model and reservation lifecycle that prevent overselling and make every stock movement auditable, reversible, and testable.

## Requirements

### Requirement: MySQL is the V1 inventory source of truth
The system SHALL determine every effective order and durable inventory transition from committed MySQL state. Redis admission MAY defer a request before that determination, but no Redis value, in-memory observation, or application-side precheck SHALL authorize an order, report a committed inventory transition, or override MySQL validation.

#### Scenario: Stale Redis observation overstates availability
- **WHEN** Redis admits a request but a competing MySQL transaction consumes the final committed unit first
- **THEN** the later MySQL transaction does not create an effective order and the Redis discrepancy is resolved without weakening inventory constraints

#### Scenario: Redis observation understates availability
- **WHEN** Redis has no trustworthy token but committed MySQL stock may still be available
- **THEN** the request receives a retryable admission result rather than a claim that MySQL inventory is durably sold out

#### Scenario: Redis is bypassed in a controlled baseline
- **WHEN** an explicit test or lab configuration selects MySQL-only admission
- **THEN** committed MySQL state continues to determine order success under all existing inventory invariants

### Requirement: Inventory balances never violate conservation
For each activity SKU, the system SHALL keep available, reserved, and sold quantities non-negative and SHALL maintain `initial = available + reserved + sold` after every committed business transaction.

#### Scenario: Successful reservation
- **WHEN** one unit is successfully reserved
- **THEN** available stock decreases by one, reserved stock increases by one, and initial and sold stock remain unchanged

#### Scenario: Successful confirmation
- **WHEN** a reserved order is successfully paid
- **THEN** reserved stock decreases by one, sold stock increases by one, and available stock remains unchanged

#### Scenario: Successful release
- **WHEN** an unpaid reservation is released
- **THEN** reserved stock decreases by one, available stock increases by one, and sold stock remains unchanged

#### Scenario: Concurrent demand exceeds stock
- **WHEN** more eligible users concurrently request an activity SKU than its initial stock
- **THEN** the number of effective orders does not exceed initial stock and no committed inventory balance is negative

### Requirement: Reservation transitions are idempotent
An inventory reservation SHALL transition only from `RESERVED` to either `CONFIRMED` or `RELEASED`, and a repeated terminal operation SHALL not change inventory again.

#### Scenario: Duplicate confirmation
- **WHEN** confirmation is requested more than once for an already confirmed reservation
- **THEN** the system reports the previously completed result and does not move reserved stock to sold again

#### Scenario: Duplicate release
- **WHEN** release is requested more than once for an already released reservation
- **THEN** the system reports the previously completed result and does not return another unit to available stock

#### Scenario: Conflicting terminal transition
- **WHEN** confirmation is attempted after release or release is attempted after confirmation
- **THEN** the system rejects the illegal transition without changing inventory

### Requirement: Stock movements are auditable
Every committed reservation, confirmation, and release SHALL have one corresponding immutable inventory movement identified by the originating business operation.

#### Scenario: Repeated business operation
- **WHEN** the same originating operation is retried
- **THEN** the system records at most one inventory movement for that operation

#### Scenario: Reconstruct balance changes
- **WHEN** an operator inspects the movements for an activity SKU
- **THEN** the ordered movements explain the difference between initial and current inventory balances
