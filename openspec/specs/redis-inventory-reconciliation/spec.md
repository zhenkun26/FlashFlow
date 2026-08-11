# redis-inventory-reconciliation Specification

## Purpose

Defines how FlashFlow detects and safely converges Redis admission state against committed MySQL order and inventory facts without inventing durable business effects.

## Requirements

### Requirement: Reconciliation derives expectations from committed MySQL state
The system SHALL treat committed MySQL inventory, effective purchase claims, orders, and reservations as authoritative when inspecting or rebuilding a Redis admission generation.

#### Scenario: Redis contains excess available tokens
- **WHEN** Redis availability could admit more effective orders than committed MySQL availability permits
- **THEN** reconciliation reports the excess and reduces or replaces the Redis generation without modifying MySQL

#### Scenario: Redis under-reports availability
- **WHEN** Redis availability plus valid active tokens is lower than the corresponding committed MySQL facts
- **THEN** reconciliation reports the deficit and may restore admission capacity only from an authoritative snapshot

#### Scenario: Redis user token conflicts with MySQL
- **WHEN** a held or confirmed Redis user token has no matching effective MySQL order and is outside its resolution window
- **THEN** reconciliation classifies it as stale or orphaned before releasing or replacing it

#### Scenario: MySQL order lacks Redis confirmation
- **WHEN** MySQL has an effective order but the corresponding Redis token is held, missing, or released
- **THEN** reconciliation records the discrepancy and converges Redis without changing the committed order or inventory

### Requirement: Ambiguous held tokens are resolved before reuse
The system SHALL resolve an expired or abandoned held token against the scoped MySQL idempotency result and effective-order state before returning its capacity to admission.

#### Scenario: Held token has a committed order
- **WHEN** reconciliation finds that an expired held token corresponds to a committed effective order
- **THEN** it confirms or reconstructs the consumed admission state and does not return capacity

#### Scenario: Held token has no committed effect
- **WHEN** reconciliation proves that an expired held token has no committed effective order or in-progress source-of-truth transaction
- **THEN** it releases the token exactly once or accounts for the release in a replacement generation

#### Scenario: Outcome cannot be proven
- **WHEN** reconciliation cannot obtain authoritative evidence for an ambiguous held token
- **THEN** it leaves the capacity unavailable and reports the SKU as requiring another reconciliation attempt

### Requirement: Rebuild uses a fenced generation
The system SHALL initialize or replace admission state under a generation fence so requests cannot observe a partially built or mixed-version SKU state.

#### Scenario: Missing Redis state is rebuilt
- **WHEN** an operator or controlled recovery process rebuilds a SKU from MySQL
- **THEN** admission remains closed until the complete new generation is published as ready atomically

#### Scenario: Request races with generation replacement
- **WHEN** an acquire or lifecycle operation references a generation that is no longer current
- **THEN** it returns a stale-generation decision without mutating the new generation

### Requirement: Reconciliation evidence is attributable and bounded
Every reconciliation run SHALL record its SKU and generation scope, authoritative snapshot boundary, discrepancy counts by bounded category, actions, unresolved items, final status, and unique run identifier without using user or request identities as metric tags.

#### Scenario: Reconciliation completes
- **WHEN** all inspected discrepancies are resolved and the rebuilt or repaired generation agrees with its authoritative snapshot
- **THEN** the report is marked passed and identifies the exact before, action, and after evidence

#### Scenario: Reconciliation cannot prove convergence
- **WHEN** MySQL or Redis is unavailable, the fence is lost, or unresolved ambiguous tokens remain
- **THEN** the report is marked blocked or failed, admission remains closed where required, and no convergence claim is made

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
