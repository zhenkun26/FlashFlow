## ADDED Requirements

### Requirement: Safe ordering avoids foreign-key lock-upgrade cycles
Each safe ordering strategy SHALL establish exclusive control of the target stock row through its reservation operation before inserting order, claim, reservation, or movement rows that reference that stock, while preserving one atomic business transaction and bounded whole-transaction retry.

#### Scenario: Distinct users contend for one hot SKU
- **WHEN** concurrent eligible users order the same SKU through a safe strategy
- **THEN** no transaction first retains a foreign-key shared lock on the stock row and then waits to upgrade that same row for reservation

#### Scenario: Same-user claim wins during stock acquisition
- **WHEN** another transaction commits the user's effective claim after the current attempt's initial claim check
- **THEN** the current command returns or retries to the stable `EXISTING_EFFECTIVE_ORDER` result without a committed stock, order, claim, reservation, or movement effect

#### Scenario: Stock becomes unavailable during contention
- **WHEN** the reservation operation observes no available unit after waiting or retrying
- **THEN** the command rechecks the effective claim before returning `SOLD_OUT` so a concurrently committed same-user order retains result precedence

#### Scenario: Ordering protocol changes internally
- **WHEN** the stock-first protocol replaces the previous child-row-first sequence
- **THEN** the synchronous HTTP statuses, result codes, idempotent replay behavior, and bounded retry contract remain compatible

## MODIFIED Requirements

### Requirement: Concurrency interleavings are deterministic where correctness depends on order
The verification suite SHALL coordinate transaction boundaries explicitly for oversell, duplicate request, duplicate payment, payment-versus-expiration races, and foreign-key lock upgrades instead of relying only on timing or random load.

#### Scenario: Payment and expiration ordering is controlled
- **WHEN** a test pauses one transaction at a defined boundary and allows the competing transaction to proceed
- **THEN** the test proves the expected legal outcome for both possible commit orders

#### Scenario: Foreign-key lock-upgrade deadlock is reproduced
- **WHEN** two database transactions insert stock-referencing child rows before attempting to update the same stock row
- **THEN** a deterministic laboratory test records the shared-to-exclusive lock cycle and identifies the rolled-back victim as expected evidence

#### Scenario: Stock-first candidate is verified
- **WHEN** the same coordinated demand executes through the candidate stock-first protocol
- **THEN** the test completes without the reproduced lock-upgrade cycle and all committed ordering invariants remain valid
