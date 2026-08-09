# Specification scenario matrix

The mapped Java tests passed on 2026-08-09 as part of the 60-test Maven suite. V1.5 evidence remains in `2026-08-09-v1-5-local.md`; V2 admission and fault evidence is in `2026-08-09-v2-local.md`.

## redis-admission-control

| Scenario | Mapped test |
|---|---|
| Generation is invisible before publication | `RedisLuaAdmissionIntegrationTest.generationIsInvisibleUntilPublishedAndRejectsStaleOperations` |
| Excess demand remains bounded | `RedisLuaAdmissionIntegrationTest.concurrentAcquireIsBoundedReplaySafeAndOnePerUser` |
| Lost acquire reply is replayed | `RedisLuaAdmissionIntegrationTest.replayAfterSimulatedLostAcquireReplyDoesNotDecrementAgain` |
| Missing/unknown state fails closed | `RedisLuaAdmissionIntegrationTest.missingOrVersionMismatchedStateFailsClosed` and `AdmissionCrossStoreFailureIntegrationTest.unavailableAdmissionFailsClosedBeforeMySqlTransaction` |
| Same key/user creates at most one effect | `RedisOrderingIntegrationTest.concurrentSameKeyAndSameUserConvergeOnOneCommittedEffect` |
| Paid and unpaid lifecycle differ | `RedisOrderingIntegrationTest.unpaidClosureReleasesConfirmedTokenButPaidOrderKeepsItConsumed` |
| Redis confirmation fails after MySQL commit | `AdmissionCrossStoreFailureIntegrationTest.mysqlCommitSurvivesAmbiguousRedisConfirmation` |
| Closure release fails after commit | `ExpirationAdmissionFailureIntegrationTest.ambiguousAfterCommitReleaseDoesNotUndoMysqlClosure` |

## redis-inventory-reconciliation

| Scenario | Mapped test |
|---|---|
| Excess/orphaned Redis state | `AdmissionReconciliationIntegrationTest.replacesExcessCapacityAndDropsOrphanedConfirmationWithoutChangingMySql` |
| Missing confirmation | `AdmissionReconciliationIntegrationTest.restoresMissingConfirmationFromCommittedMySqlFacts` |
| Ambiguous held token | `AdmissionReconciliationIntegrationTest.ambiguousHeldAdmissionWithholdsCapacityAndLeavesGenerationUnpublished` |
| Missing capacity and stale terminal state | `AdmissionReconciliationIntegrationTest.restoresMissingCapacityAndDropsTerminalAndProvenNonEffectiveTokens` |
| Redis unavailable while reconciling | `AdmissionReconciliationFailureTest.redisOutageStillProducesAppendOnlyBlockedEvidence` |

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
| User retries after unpaid closure | `PaymentAndExpirationIntegrationTest.expirationWinsAndRepeatedLatePaymentCreatesOneCompensationCase` directly places a new order and verifies one new effective claim after release |
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
| Optimistic conflicts exceed retry budget | `StrategyInvariantIntegrationTest.deterministicOptimisticConflictExhaustsBudgetWithoutPartialEffects` |
| Payment and expiration ordering is controlled | the two ordered payment/expiration integration tests |
| Correct strategy run completes | strategy invariant suite and the passing `ExperimentEvidenceReporter` matrix evidence |
| Verification cannot execute | `ExperimentEvidenceReporterTest` verifies `BLOCKED`; failed startup attempts during development were retained as `BLOCKED`, not promoted to `PASS` |
