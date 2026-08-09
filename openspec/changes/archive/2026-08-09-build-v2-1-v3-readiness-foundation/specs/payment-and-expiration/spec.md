## ADDED Requirements

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
