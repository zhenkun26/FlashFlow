## Purpose

Defines reproducible database-concurrency experiments that demonstrate overselling failure modes, compare correct control strategies, and retain evidence without exposing unsafe behavior in normal runtime.

## ADDED Requirements

### Requirement: Unsafe read-then-write is isolated to the laboratory
The system SHALL make the unprotected read-then-write stock strategy available only in a dedicated test or lab environment and SHALL prevent normal runtime configuration from selecting it.

#### Scenario: Lab reproduces overselling
- **WHEN** coordinated concurrent requests execute the unsafe strategy against deliberately limited stock
- **THEN** the experiment can demonstrate a violated order or inventory invariant and labels the run as an expected unsafe result

#### Scenario: Normal runtime requests unsafe strategy
- **WHEN** normal runtime configuration attempts to select the unsafe strategy
- **THEN** application startup or configuration validation fails before serving order traffic

### Requirement: Correct MySQL strategies are comparable
The experiment suite SHALL exercise pessimistic locking, optimistic locking with bounded retries, and conditional atomic update against the same workload and invariants.

#### Scenario: Strategy faces excess demand
- **WHEN** a correct strategy processes more distinct eligible users than available units
- **THEN** all committed results preserve non-negative inventory, stock conservation, and the effective-order limit

#### Scenario: Optimistic conflicts exceed retry budget
- **WHEN** an optimistic-lock request repeatedly loses version conflicts beyond its configured retry budget
- **THEN** it returns a retryable contention result without creating a partial order or inventory effect

### Requirement: Concurrency interleavings are deterministic where correctness depends on order
The verification suite SHALL coordinate transaction boundaries explicitly for oversell, duplicate request, duplicate payment, and payment-versus-expiration races instead of relying only on timing or random load.

#### Scenario: Payment and expiration ordering is controlled
- **WHEN** a test pauses one transaction at a defined boundary and allows the competing transaction to proceed
- **THEN** the test proves the expected legal outcome for both possible commit orders

### Requirement: Every experiment records invariant evidence
Each comparison run SHALL report workload inputs, strategy, success and rejection counts, latency distribution, retry or lock-conflict counts, final inventory balances, effective-order count, and invariant-check results.

#### Scenario: Correct strategy run completes
- **WHEN** a correct-strategy experiment finishes
- **THEN** its report marks each invariant as passed only after querying committed database state

#### Scenario: Verification cannot execute
- **WHEN** required infrastructure or tooling is unavailable
- **THEN** the run is reported as blocked rather than passed and no unsupported performance claim is produced

