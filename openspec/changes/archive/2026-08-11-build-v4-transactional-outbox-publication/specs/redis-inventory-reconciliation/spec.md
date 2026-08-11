## ADDED Requirements

### Requirement: Reconciliation accounts for durable Outbox work
The system SHALL use the durable command, Outbox disposition, and committed MySQL business facts together when classifying admission held by an asynchronously accepted V4 command, and SHALL NOT release capacity solely because publication is delayed, retried, exhausted, or ambiguous.

#### Scenario: Accepted Outbox item awaits publication
- **WHEN** a held admission maps to a committed ready, claimed, or retryable Outbox item without a terminal business result
- **THEN** reconciliation treats the command as durable in-flight work and keeps its capacity unavailable

#### Scenario: Outbox item was acknowledged
- **WHEN** an admission maps to a broker-acknowledged Outbox item whose consumer result is not yet terminal
- **THEN** reconciliation retains the admission and reports the command as awaiting authoritative business resolution

#### Scenario: Outbox publication is exhausted
- **WHEN** an admission maps to an exhausted or invalid Outbox item and no committed order result exists
- **THEN** reconciliation reports bounded operator-required work and does not infer safe release from the transport disposition alone

#### Scenario: No durable or in-progress effect is proven
- **WHEN** authoritative inspection proves no command acceptance, Outbox work, committed business effect, or source-of-truth transaction can still consume the admission
- **THEN** reconciliation may release or replace the held capacity exactly once under its existing fence

#### Scenario: Outbox evidence is unavailable or contradictory
- **WHEN** required command or Outbox evidence cannot be read or conflicts with committed MySQL facts
- **THEN** the SKU remains closed where necessary and reconciliation is not marked passed
