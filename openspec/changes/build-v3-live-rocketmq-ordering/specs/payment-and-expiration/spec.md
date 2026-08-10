## ADDED Requirements

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

