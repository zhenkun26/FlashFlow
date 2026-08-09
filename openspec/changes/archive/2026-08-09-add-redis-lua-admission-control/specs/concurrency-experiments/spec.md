## ADDED Requirements

### Requirement: Redis admission is compared against the committed MySQL baseline
The experiment suite SHALL compare Redis admission with the stock-first MySQL-only baseline using controlled workloads that hold business data, MySQL strategy, concurrency, duration, pool, retry budgets, and SKU contention shape constant except for the declared admission mode.

#### Scenario: Admission comparison executes
- **WHEN** the operator runs the canonical MySQL-only and Redis-admission pair
- **THEN** both reports identify revision and resolved inputs and compare Redis decisions, MySQL transaction starts, HTTP outcomes, latency, retries, and committed-state invariants separately

#### Scenario: Redis reduces hot-row traffic
- **WHEN** excess demand is rejected by a trustworthy Redis decision
- **THEN** the report demonstrates the corresponding request did not start a MySQL ordering transaction without presenting the local result as production capacity

### Requirement: Redis failure and drift drills preserve source-of-truth invariants
The verification suite SHALL exercise Redis timeout or unavailability, lost or stale generations, ambiguous script replies, duplicate lifecycle operations, and Redis/MySQL drift through deterministic tests or controlled fault drills.

#### Scenario: Redis becomes unavailable during ordering
- **WHEN** the failure drill prevents a trustworthy admission decision
- **THEN** no new MySQL ordering attempt starts through automatic fallback and the technical outcome is classified distinctly

#### Scenario: Admission reply is lost
- **WHEN** the acquire script executes but the application observes a timeout
- **THEN** replay with the same idempotency identity proves that no additional token or durable business effect is created

#### Scenario: Lifecycle operation repeats
- **WHEN** token confirmation or release is delivered repeatedly
- **THEN** the experiment records one lifecycle effect and preserves non-negative bounded Redis availability

#### Scenario: Redis state disagrees with MySQL
- **WHEN** a controlled fixture creates excess, missing, orphaned, or stale admission state
- **THEN** reconciliation detects the declared discrepancy and all committed MySQL inventory and ordering invariants remain valid

### Requirement: V2 evidence classifies admission and convergence outcomes
Every Redis-enabled experiment SHALL record bounded counts for admission grants, no-token and duplicate-user decisions, not-ready and unavailable decisions, MySQL attempts avoided and started, token confirmations, safe releases, quarantined ambiguous outcomes, drift categories, reconciliation actions, and unexpected failures.

#### Scenario: Redis-enabled run completes
- **WHEN** a V2 characterization finishes
- **THEN** request totals and admission decisions reconcile, MySQL committed state passes all existing invariant queries, and the report distinguishes Redis observations from durable business outcomes

#### Scenario: Required Redis evidence is missing
- **WHEN** the workload completes but admission, lifecycle, or reconciliation evidence cannot be collected or reconciled
- **THEN** the report is failed or blocked and its latency or request rate is not presented as successful V2 evidence
