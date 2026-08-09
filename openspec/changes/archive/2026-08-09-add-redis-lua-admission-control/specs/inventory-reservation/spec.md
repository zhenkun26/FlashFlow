## MODIFIED Requirements

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
