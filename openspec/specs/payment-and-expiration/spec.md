# payment-and-expiration Specification

## Purpose

Defines simulated payment, unpaid-order expiration, and race semantics so repeated callbacks and competing terminal actions cannot apply payment or stock effects more than once.

## Requirements

### Requirement: Payment callbacks are idempotent
The system SHALL identify each simulated provider callback by a stable provider event identifier and provider transaction identifier, and repeated callbacks SHALL produce at most one payment effect.

#### Scenario: Duplicate successful callback
- **WHEN** the same successful payment callback is delivered multiple times for a pending-payment order
- **THEN** exactly one callback marks the payment successful, marks the order paid, and confirms the reservation

#### Scenario: Provider transaction reused for another order
- **WHEN** a provider transaction identifier already bound to one order is submitted for another order
- **THEN** the system rejects the callback without modifying either order

### Requirement: Order status transitions are legal and conditional
The system SHALL allow only `PENDING_PAYMENT` to `PAID` or `CLOSED_UNPAID` in V1, and each transition SHALL verify the expected source state in the same transaction as its side effects.

#### Scenario: Payment wins the race
- **WHEN** payment confirmation commits before an expiration attempt for the same order
- **THEN** the order remains paid, its reservation remains confirmed, and expiration performs no stock change

#### Scenario: Expiration wins the race
- **WHEN** expiration commits before a successful payment callback for the same order
- **THEN** the order remains closed unpaid, its reservation remains released, and the callback cannot recreate or reconfirm the order

#### Scenario: Illegal direct transition
- **WHEN** an operation attempts a transition not defined by the V1 order state machine
- **THEN** the operation is rejected and no payment, order, claim, or inventory side effect is committed

### Requirement: Expired unpaid reservations are eventually released
The system SHALL identify pending-payment orders whose expiration time has passed and SHALL eventually close each eligible order, release its reservation, and remove its effective-purchase claim atomically.

#### Scenario: Expiration worker processes an unpaid order
- **WHEN** a pending-payment order is past its expiration time and has no successful payment
- **THEN** the order becomes closed unpaid, the reservation is released, and the user may try to order again

#### Scenario: Expiration processing repeats
- **WHEN** the expiration worker selects an order that another worker has already closed
- **THEN** the repeated attempt makes no additional stock or order change

#### Scenario: Worker interruption
- **WHEN** an expiration attempt stops before its database transaction commits
- **THEN** no partial closure is visible and a later scan can process the order again

### Requirement: Late successful payments are visible for compensation
The system SHALL preserve a successful provider result received after unpaid closure, SHALL not revive the closed order, and SHALL create exactly one visible refund-required compensation record.

#### Scenario: Successful callback arrives after closure
- **WHEN** a valid successful payment callback arrives after the order has committed as closed unpaid
- **THEN** the payment is recorded as successful with an unapplied refund-required outcome, the order remains closed unpaid, and one compensation record is available for manual recovery

#### Scenario: Late callback repeats
- **WHEN** the same late successful callback is delivered repeatedly
- **THEN** the system retains one payment result and one refund-required compensation record

### Requirement: Delayed expiration triggers are advisory and duplicate-safe
The system SHALL treat a future delayed message as a trigger to attempt the existing MySQL-authoritative unpaid-order closure and SHALL retain a committed-state scanner as the recovery path for missing, delayed, or lost triggers.

#### Scenario: Delayed trigger arrives after expiry
- **WHEN** a delayed trigger addresses an order whose expiration time has passed and whose state is still pending payment
- **THEN** the existing transactional closure applies at most once and releases the reservation, claim, inventory, and Redis admission through their established commit boundaries

#### Scenario: Trigger arrives early or after payment
- **WHEN** a trigger arrives before the committed expiration time or after payment has committed
- **THEN** it does not close the order or change inventory and the legal terminal state is preserved

#### Scenario: Trigger is missing or duplicated
- **WHEN** a delayed trigger is lost, delayed excessively, redelivered, or races with the database scanner
- **THEN** the scanner still eventually processes eligible orders and all competing attempts converge through the same locked conditional transition

### Requirement: V3 publishes advisory delayed expiration triggers
The system SHALL attempt to publish one versioned delayed expiration trigger after an effective pending-payment order commits, SHALL never make order commit depend on that publication, and SHALL retain database scanning as recovery when publication or delivery is missing or ambiguous.

#### Scenario: Pending-payment order commits
- **WHEN** asynchronous command consumption commits a new pending-payment order
- **THEN** an after-commit action attempts delayed publication using the order identity and committed expiration time without carrying stock authority

#### Scenario: Delayed publication fails or is ambiguous
- **WHEN** the trigger cannot be published or its acknowledgement is lost
- **THEN** the committed order remains valid, the failure is observable, and the database scanner remains able to close it after expiry

### Requirement: Live delayed delivery converges with payment and scanning
The system SHALL consume live delayed triggers through the existing order-specific locked expiration boundary and SHALL acknowledge each trigger after a recoverable terminal trigger outcome.

#### Scenario: Trigger closes an eligible order
- **WHEN** the committed expiration time has passed and the order remains pending payment
- **THEN** the existing transaction closes and releases the order at most once before the delivery is acknowledged

#### Scenario: Trigger arrives early
- **WHEN** the trigger arrives before the committed expiration time
- **THEN** the order remains unchanged and recovery remains scheduled through bounded redelivery or the scanner

#### Scenario: Trigger duplicates or races with scanner
- **WHEN** duplicate triggers, payment, and the database scanner act concurrently
- **THEN** all attempts converge on one legal committed terminal state and no stock, claim, reservation, or Redis lifecycle effect is duplicated
