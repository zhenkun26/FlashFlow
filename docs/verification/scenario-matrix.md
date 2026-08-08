# Specification scenario matrix

The mapped Java tests passed on 2026-08-08 as part of the 20-test Maven suite. The HTTP characterization and final SQL balances are recorded in `2026-08-08-v1-local.md`.

## synchronous-ordering

| Scenario | Mapped test |
|---|---|
| Eligible user creates an order | `OrderIntegrationTest.createsAndReplaysOneCommittedOrder` |
| Activity is not accepting orders | `OrderIntegrationTest.rejectsInactiveSoldOutExistingAndConflictingIdempotency` |
| Stock is unavailable | `OrderIntegrationTest.rejectsInactiveSoldOutExistingAndConflictingIdempotency` |
| Sequential duplicate request | `OrderIntegrationTest.createsAndReplaysOneCommittedOrder` |
| Concurrent duplicate requests | `ConcurrentIdempotencyIntegrationTest.sameKeyAndSameUserProduceOneBusinessEffect` |
| Key reused with different payload | `OrderIntegrationTest.rejectsInactiveSoldOutExistingAndConflictingIdempotency` |
| User already has an effective order | `OrderIntegrationTest.rejectsInactiveSoldOutExistingAndConflictingIdempotency` |
| User retries after unpaid closure | `PaymentAndExpirationIntegrationTest.expirationWinsAndRepeatedLatePaymentCreatesOneCompensationCase` plus a follow-up order assertion to add if the existing evidence proves insufficient |
| Different users compete for the same SKU | `StrategyInvariantIntegrationTest.safeStrategiesPreserveInvariantsUnderExcessDemand` |
| Response is lost after commit | `OrderIntegrationTest.createsAndReplaysOneCommittedOrder` models retry of a committed result |

## inventory-reservation

| Scenario | Mapped test |
|---|---|
| Stale application observation | safe strategy excess-demand test and unsafe control comparison |
| Successful reservation | `OrderIntegrationTest.createsAndReplaysOneCommittedOrder` |
| Successful confirmation | `PaymentAndExpirationIntegrationTest.appliesPaymentExactlyOnceAndExpirationLoses` |
| Successful release | `PaymentAndExpirationIntegrationTest.expirationWinsAndRepeatedLatePaymentCreatesOneCompensationCase` |
| Concurrent demand exceeds stock | `StrategyInvariantIntegrationTest.safeStrategiesPreserveInvariantsUnderExcessDemand` |
| Duplicate confirmation | duplicate payment callback test |
| Duplicate release | repeated expiration scan test |
| Conflicting terminal transition | payment-wins and expiration-wins tests |
| Repeated business operation | duplicate payment and repeated expiration tests |
| Reconstruct balance changes | `InvariantService` query plus movement assertions in the passing strategy suite |

## payment-and-expiration

| Scenario | Mapped test |
|---|---|
| Duplicate successful callback | `PaymentAndExpirationIntegrationTest.appliesPaymentExactlyOnceAndExpirationLoses` |
| Provider transaction reused for another order | `PaymentAndExpirationIntegrationTest.rejectsProviderTransactionReuseAcrossOrders` |
| Payment wins the race | `PaymentAndExpirationIntegrationTest.appliesPaymentExactlyOnceAndExpirationLoses` |
| Expiration wins the race | `PaymentAndExpirationIntegrationTest.expirationWinsAndRepeatedLatePaymentCreatesOneCompensationCase` |
| Illegal direct transition | `DomainStateTest` and payment/expiration losing-operation assertions |
| Expiration worker processes an unpaid order | expiration-wins test |
| Expiration processing repeats | expiration-wins test |
| Worker interruption | `ExpirationRollbackIntegrationTest.interruptionBeforeCommitRollsBackAllExpirationEffects` |
| Successful callback arrives after closure | expiration-wins test |
| Late callback repeats | expiration-wins test |

## concurrency-experiments

| Scenario | Mapped test |
|---|---|
| Lab reproduces overselling | `UnsafeInterleavingTest.deterministicBarrierReproducesLostUpdate` |
| Normal runtime requests unsafe strategy | `UnsafeStrategyGuardTest.rejectsUnsafeStrategyWithoutLabProfile` |
| Strategy faces excess demand | `StrategyInvariantIntegrationTest.safeStrategiesPreserveInvariantsUnderExcessDemand` |
| Optimistic conflicts exceed retry budget | bounded whole-transaction retry returns `RETRYABLE_CONTENTION`; HTTP load evidence includes the mapped 503 overload response count |
| Payment and expiration ordering is controlled | the two ordered payment/expiration integration tests |
| Correct strategy run completes | strategy invariant suite and `ExperimentReporter` |
| Verification cannot execute | `current-status.md` defines and preserves the `BLOCKED` policy; it was not triggered in the final run |
