## ADDED Requirements

### Requirement: Synchronous and asynchronous execution converge
Any transport-neutral asynchronous command harness SHALL invoke the same durable ordering boundary as the synchronous endpoint and SHALL preserve its request fingerprint, result precedence, bounded whole-transaction retries, purchase-claim uniqueness, and inventory invariants.

#### Scenario: Both paths receive the same business request
- **WHEN** the synchronous endpoint and the asynchronous harness execute equivalent scoped requests under the same committed starting state
- **THEN** they expose compatible final business results and neither path can bypass MySQL validation

#### Scenario: Paths race for one identity
- **WHEN** synchronous and asynchronous execution race with the same scoped idempotency identity
- **THEN** they converge on one completed idempotency record and at most one effective order
