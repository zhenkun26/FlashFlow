# Specification scenario matrix

The V1/V2 mapped Java tests passed on 2026-08-09 as part of the 60-test V2 Maven suite. V2.1 evidence remains in `2026-08-09-v2-1-local.md`. V3 live mappings below were exercised on 2026-08-10 unless explicitly marked as a deterministic-only boundary; the current roll-up is in `2026-08-10-v3-local.md`.

## v4-transactional-outbox-publication

The mappings below are implementation targets for the active V4 change. They remain `NOT_RUN` until the named gates execute against an attributable V4 revision.

| Scenario | Mapped test/evidence |
|---|---|
| Command and Outbox commit atomically | `OutboxAcceptanceIntegrationTest.commandAndOutboxCommitAsOneAcceptedPair` |
| Acceptance transaction rolls back | `OutboxAcceptanceIntegrationTest.persistenceFaultsExposeNoPartialAcceptedPair` |
| Response is lost after commit | `OutboxAcceptanceIntegrationTest.sameIdentityRecoversCommittedAcceptanceAfterLostResponse` |
| Competing dispatchers claim work | `OutboxClaimIntegrationTest.concurrentDispatchersHoldOneActiveLease` |
| Dispatcher stops after claim | `OutboxClaimIntegrationTest.expiredLeaseIsTakenOver` and real `V4OutboxRocketMqRecoveryIntegrationTest` |
| No work is eligible | `OutboxDispatcherTest.emptyPollAndTerminalRowsDoNothing` |
| Broker acknowledges publication | `OutboxDispatcherTest.sendOkRecordsAcknowledgementForCurrentLease` and real V4 gate |
| Broker is temporarily unavailable | `V4OutboxRocketMqRecoveryIntegrationTest.brokerOutageLeavesBacklogAndRecoveryPublishesWithoutCallerRetry` |
| Producer acknowledgement is lost | `V4OutboxRocketMqRecoveryIntegrationTest.lostProducerAcknowledgementRepublishesOneBusinessEffect` |
| Dispatcher stops after Broker acknowledgement | `V4OutboxRocketMqRecoveryIntegrationTest.stopAfterSendOkRecoversThroughDuplicateIdentity` |
| Retry budget is exhausted | `OutboxDispatcherTest.retryBudgetProducesInspectableExhaustion` and real failure gate |
| Stored envelope is invalid | `OutboxDispatcherTest.invalidImmutableEnvelopeIsNotPublished` |
| Operator inspects a backlog | `OutboxMetricsTest` and the append-only V4 local report |
| Accepted work cannot be reconciled | `ExperimentEvidenceReporterTest.rejectsUnexplainedV4Acceptance` |

## v4-asynchronous-order-contract

| Scenario | Mapped test/evidence |
|---|---|
| Durable command awaits dispatch | `OutboxAsyncOrderControllerTest.durablePairReturns202BeforeBrokerAcknowledgement` |
| Durable acceptance cannot commit | `OutboxAcceptanceIntegrationTest.persistenceFaultsExposeNoPartialAcceptedPair` |
| Accepted command is queried before delivery | `OutboxAsyncOrderControllerTest.statusKeepsTransportDetailsPrivate` |
| Accepted command later completes | `V4OutboxRocketMqEndToEndIntegrationTest.acceptanceDispatchAndConsumptionConverge` |
| Direct mode handles a request | existing `AsyncOrderApplicationServiceTest` plus `MessagingModeIntegrationTest.directKeepsV3Contract` |
| Outbox mode handles a request | `OutboxAsyncOrderControllerTest.durablePairReturns202BeforeBrokerAcknowledgement` |

## v4-rocketmq-order-runtime

| Scenario | Mapped test/evidence |
|---|---|
| Outbox mode starts with complete configuration | `MessagingConfigurationGuardTest.completeOutboxConfigurationPasses` |
| Outbox configuration is incomplete | `MessagingConfigurationGuardTest.outboxModeRejectsUnsafeDispatcherBounds` |
| Direct comparison mode is selected | `MessagingModeIntegrationTest.directKeepsV3ContractAndCreatesNoOutboxWork` |
| Messaging is disabled | `MessagingModeIntegrationTest.disabledCreatesNoMessagingOrDispatcherComponents` |
| Direct and Outbox carry equivalent commands | `V4ControlledComparisonIntegrationTest.directAndOutboxReuseEnvelopeAndConsumerSemantics` |
| Outbox publication is duplicated | `V4OutboxRocketMqRecoveryIntegrationTest.lostProducerAcknowledgementRepublishesOneBusinessEffect` |

## v4-redis-outbox-reconciliation

| Scenario | Mapped test/evidence |
|---|---|
| Accepted Outbox item awaits publication | `AdmissionReconciliationIntegrationTest.retainsReadyClaimedAndRetryableOutboxAdmissions` |
| Outbox item was acknowledged | `AdmissionReconciliationIntegrationTest.retainsAcknowledgedNonterminalOutboxAdmission` |
| Outbox publication is exhausted | `AdmissionReconciliationIntegrationTest.exhaustedOrInvalidOutboxRequiresOperatorEvidence` |
| No durable or in-progress effect is proven | `AdmissionReconciliationIntegrationTest.releasesOnlyProvenNoEffectOutboxAdmission` |
| Outbox evidence is unavailable or contradictory | `AdmissionReconciliationIntegrationTest.blocksMissingOrContradictoryOutboxEvidence` |

## v4-concurrency-experiments

| Scenario | Mapped test/evidence |
|---|---|
| V4 workload and drain complete | `V4ControlledComparisonIntegrationTest` and revision-bound V4 local report |
| Durable accepted work is unexplained | `ExperimentEvidenceReporterTest.rejectsUnexplainedV4Acceptance` |
| Claim expires during a fault drill | `OutboxClaimIntegrationTest.expiredLeaseIsTakenOver` plus retained identity evidence |
| Direct and Outbox runs are compared | `V4ControlledComparisonIntegrationTest.directAndOutboxReportSeparateLatencyDimensions` |
| Broker outage spans acceptance | `V4ControlledComparisonIntegrationTest.outageSeparatesDirectNonAcceptanceFromOutboxRecovery` |
| Local comparison completes | revision-bound V4 local report with machine, container, dataset, revision, fault, and local-scope fields |

## v3-live-rocketmq-ordering

| Scenario | Mapped test/evidence |
|---|---|
| `202` only after real Broker acknowledgement and caller-scoped status | `LiveRocketMqEndToEndIntegrationTest.httpBoundaryThroughLiveBrokerCommitsOnceAndDelayedTriggerClosesOnce` |
| Broker unavailable does not claim acceptance; same identity recovers | `LiveRocketMqEndToEndIntegrationTest.brokerOutageDoesNotClaimAcceptanceAndSameIdentityRecoversAfterRestart` |
| Existing synchronous HTTP semantics remain available in live mode | `LiveRocketMqEndToEndIntegrationTest.synchronousV1KeepsCreatedAndReplaySemanticsWhileLiveMessagingIsEnabled` |
| Poison envelope reaches the dedicated real DLQ with bounded metadata | `LiveRocketMqEndToEndIntegrationTest.poisonEnvelopeIsPublishedToTheRealDeadLetterTopicWithBoundedMetadata` |
| Live delayed trigger closes once and reconciles Redis/MySQL | `LiveRocketMqEndToEndIntegrationTest.httpBoundaryThroughLiveBrokerCommitsOnceAndDelayedTriggerClosesOnce` |
| Missing/late delayed delivery is recovered by the scanner | `LiveRocketMqScannerRecoveryIntegrationTest.scannerClosesWhenTheBrokerDelayExceedsTheDeclaredRecoveryBound` |
| Lost acknowledgement and interruption recovery | `OrderCommandConsumerIntegrationTest` and `InProcessOrderCommandConsumerTest` (deterministic boundary injection) |
| Real consumer acknowledgement loss and redelivery | `LiveRocketMqRedeliveryIntegrationTest.lostAcknowledgementRedeliversAndRecoversOneCommittedResult` |
| Retry exhaustion, manual same-identity replay, and admission retention | `LiveRocketMqRetryExhaustionIntegrationTest.retryExhaustionDeadLettersWithoutBusinessEffectOrAdmissionRelease` |
| Expiration acknowledgement loss and duplicate closure prevention | `LiveRocketMqExpirationAckLossIntegrationTest.lostExpirationAcknowledgementRedeliversWithoutDuplicateClosure` |
| Retry exhaustion and unsupported envelope disposition | `RocketMqListenerContractTest` (deterministic) plus the real poison-envelope and retry-exhaustion tests above |
| Prepared, ambiguous, retryable, and dead-lettered reconciliation | `AdmissionReconciliationIntegrationTest.classifiesPreparedAmbiguousAndDeadLetteredCommandsWithoutAssumingBusinessTruth` |
| Identity-level evidence refuses unreconciled or in-flight PASS | `ExperimentEvidenceReporterTest.requiresIdentityLevelMessagingReconciliationForLiveRuns` |
| Synchronous completion versus V3 acceptance/completion | `V3ControlledComparisonIntegrationTest.separatesSynchronousCompletionFromV3AcceptanceAndCompletion` |

## messaging-command-foundation

| Scenario | Mapped test |
|---|---|
| Envelope version and serialization compatibility | `OrderCommandContractTest.serializesVersionOneAndRejectsUnsupportedVersion` |
| Stable private identity and conflicting replay | `OrderCommandContractTest.derivesStableIdentityAndRejectsConflictingReplay` and `CommandLedgerIntegrationTest` |
| Concurrent ledger claim converges | `CommandLedgerIntegrationTest.concurrentCreateAndClaimConvergesAndTerminalStateCannotBeOverwritten` |
| Sequential/concurrent duplicate delivery | `OrderCommandConsumerIntegrationTest` duplicate-delivery cases |
| Interruption before commit | `OrderCommandConsumerIntegrationTest.interruptionBeforeCommitLeavesNoPartialEffect` |
| Commit before acknowledgement loss | `OrderCommandConsumerIntegrationTest.interruptionAfterCommitBeforeAckReplaysDurableResult` |
| Synchronous versus command race | `OrderCommandConsumerIntegrationTest.synchronousAndCommandRaceConverges` |
| Publication result decision matrix | `DeterministicPublicationCoordinatorTest` |

## delayed-expiration-readiness

| Scenario | Mapped test |
|---|---|
| Early, duplicate, and paid-order triggers | `DelayedExpirationIntegrationTest` |
| Missing trigger recovered by scanner | `DelayedExpirationIntegrationTest.missingTriggerIsRecoveredByScanner` |
| Trigger versus scanner | `DelayedExpirationIntegrationTest.triggerAndScannerRaceClosesOnce` |
| Payment versus trigger | `DelayedExpirationIntegrationTest.paymentAndTriggerOrderingPreservesTerminalWinner` |

## rocketmq-compatibility-spike

| Scenario | Mapped evidence |
|---|---|
| Pinned topology starts and responds to admin probe | `scripts/run-rocketmq-spike.sh` append-only report; status is recorded separately |
| Duplicate, redelivery, poison, and unsupported envelope | `SyntheticMessagingSpikeTest` |
| Lost producer response and acknowledgement | `SyntheticMessagingSpikeTest` |
| Consumer interruption and broker restart classification | `SyntheticMessagingSpikeTest` |
| Delay observation is bounded and not an SLA | `SyntheticMessagingSpikeTest.failuresAndDelayAreBoundedEvidence` |
| Reconciled append-only readiness report | `MessagingReadinessReporterTest` |

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
