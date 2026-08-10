## 1. Runtime Boundary and Dependency Guards

- [x] 1.1 Add the pinned RocketMQ Java client and verify the resolved client/Broker compatibility inputs are recorded without loading a client in default mode.
- [x] 1.2 Extend messaging configuration with an explicit live V3 mode, broker endpoints, order and expiration topics, distinct consumer groups, acknowledgement, timeout, retry, dead-letter, and delay settings.
- [x] 1.3 Implement startup validation that rejects incomplete, contradictory, unsafe, or unpinned live settings before traffic is served.
- [x] 1.4 Add source and application-context tests proving `DISABLED` remains broker-free, the spike stays isolated, and only live mode creates clients and asynchronous routes.
- [x] 1.5 Extend disposable Compose/Testcontainers infrastructure with deterministic topic and group provisioning, health checks, bounded resources, and cleanup.

## 2. Command Publication and Durable Acceptance

- [x] 2.1 Add a live producer adapter that serializes the existing versioned envelope, uses the stable command ID as broker key, and emits a separate bounded transport-attempt identity.
- [x] 2.2 Extend command-ledger operations for attributable prepare, accepted, retryable, and unresolved publication transitions without adding a payload queue or dispatcher.
- [x] 2.3 Implement the asynchronous orchestration sequence for validation, completed replay, Redis admission, command preparation, bounded direct publication, and publication classification.
- [x] 2.4 Map trustworthy Broker acknowledgement to `ACCEPTED`, definitive no-publication to safe release and retryable state, and ambiguity to `UNRESOLVED` plus retained or quarantined admission.
- [x] 2.5 Verify same-identity retries after definitive failure, lost acknowledgement, and a prepared-before-send interruption create no duplicate command, admission, or business effect.
- [x] 2.6 Add bounded producer metrics and logs for prepared, acknowledged, definitive failure, ambiguous, retry, and unresolved outcomes without sensitive or high-cardinality fields.

## 3. Asynchronous HTTP and Status Contract

- [x] 3.1 Add the live-mode `/api/v2/orders` endpoint using the existing request validation and scoped idempotency identity.
- [x] 3.2 Return `202 Accepted`, stable command ID, and status location only after trustworthy Broker acknowledgement; map definitive and ambiguous publication to bounded retryable responses.
- [x] 3.3 Add the caller-scoped command-status resource for prepared, accepted, processing, completed, rejected, retryable, and unresolved states.
- [x] 3.4 Include an order ID or business result only when durable ledger or committed MySQL evidence proves it, and return not found without cross-caller disclosure.
- [x] 3.5 Add HTTP contract tests for acceptance, replay, conflicting payload, unsupported input, publication failure, publication ambiguity, status progression, terminal recovery, and unknown identity.
- [x] 3.6 Re-run explicit `/api/v1/orders` compatibility tests in disabled and live configurations and prove it retains its synchronous result semantics.

## 4. Live At-Least-Once Order Consumption

- [x] 4.1 Add a live order-command listener that validates the envelope and delegates to the existing transport-neutral consumer and ordering boundary.
- [x] 4.2 Implement manual acknowledgement eligibility only after a durable recoverable result or deterministic non-retryable validation outcome.
- [x] 4.3 Bind retryable processing failures to bounded Broker redelivery without converting them into committed business rejection.
- [x] 4.4 Verify sequential and concurrent duplicate deliveries converge on one command result and at most one order, claim, reservation, movement, and idempotency effect.
- [x] 4.5 Add deterministic interruption tests before MySQL commit, after MySQL commit, after ledger completion, and before Broker acknowledgement.
- [x] 4.6 Add real-Broker restart and redelivery tests proving committed result recovery after consumer restart and lost acknowledgement.
- [x] 4.7 Add bounded delivery, redelivery, claim, outcome, and acknowledgement metrics correlated by privacy-preserving command identity only in retained diagnostic evidence.

## 5. Retry Exhaustion and Dead-Letter Handling

- [x] 5.1 Define and provision a dedicated dead-letter topic and bounded consumer-attempt policy for live order commands.
- [x] 5.2 Route malformed, conflicting, unsupported-version, and retry-exhausted deliveries to inspectable dead-letter evidence without executing an unsupported order.
- [x] 5.3 Persist or report bounded dead-letter reason, command ID, schema version, source topic, and attempt metadata without raw caller or idempotency values.
- [x] 5.4 Ensure dead-lettering alone never releases Redis admission and that command state remains rejected, retryable, or unresolved according to durable evidence.
- [x] 5.5 Add deterministic and real-Broker tests for transient recovery, attempt exhaustion, poison envelopes, dead-letter visibility, and manual same-identity replay.

## 6. Redis Admission and Reconciliation Integration

- [x] 6.1 Connect real producer outcomes to the existing release, retain, and quarantine publication decision matrix.
- [x] 6.2 Connect live consumer terminal outcomes to idempotent admission confirmation, safe release, or quarantine from committed MySQL evidence.
- [x] 6.3 Extend fenced reconciliation to classify aged prepared, ambiguous, retryable, and dead-lettered commands without treating transport state as business truth.
- [x] 6.4 Add fault tests for Redis loss during publication classification, after consumer commit, during lifecycle transition, and during reconciliation.
- [x] 6.5 Verify final Redis generation accounting, active-user ownership, quarantined identities, and committed MySQL inventory remain reconcilable after every live transport drill.

## 7. Broker-Backed Delayed Expiration

- [x] 7.1 Add an after-commit publisher for the existing versioned delayed-expiration envelope using committed order identity and expiration time only.
- [x] 7.2 Record delayed publication failure or ambiguity without rolling back or misreporting the committed order.
- [x] 7.3 Add a live delayed-trigger listener that calls the existing order-specific locked expiration boundary and acknowledges only a recoverable trigger outcome.
- [x] 7.4 Define bounded handling for early triggers while retaining the configured database scanner as the eventual recovery path.
- [x] 7.5 Add deterministic and real-Broker tests for on-time, early, late, duplicate, missing, and lost-ack triggers plus payment and scanner races.
- [x] 7.6 Verify each eligible order closes at most once, committed stock and claim invariants hold, and scanner recovery is measured without claiming a delay SLA.

## 8. Live Verification and Characterization

- [x] 8.1 Extend the experiment manifest and schema with live mode, client and Broker identity, topic/group names, acknowledgement, timeout, retry, dead-letter, delay, drain, and injected-fault inputs.
- [x] 8.2 Extend evidence capture to reconcile HTTP requests, prepared and accepted commands, publication classes, deliveries, retries, dead letters, final command states, Redis lifecycle, and committed MySQL outcomes by stable identity.
- [x] 8.3 Add a real end-to-end gate traversing HTTP, Redis, producer, RocketMQ, consumer, command ledger, MySQL, status query, delayed trigger, and scanner.
- [x] 8.4 Add live fault cases for Broker unavailable, producer timeout/lost acknowledgement, duplicate delivery, consumer interruption, acknowledgement loss, retry exhaustion, dead letter, delayed-message loss, and scanner recovery.
- [x] 8.5 Reject `PASS` when required work remains in flight, evidence counts do not reconcile, committed invariants fail, or any required boundary is simulated, stale, `FAIL`, `BLOCKED`, or `NOT_RUN`.
- [x] 8.6 Add a controlled synchronous-versus-V3 local comparison that separates acceptance latency, completion latency, business results, technical outcomes, and message observations without making a production capacity claim.

## 9. Release Gates and Documentation

- [x] 9.1 Run the complete Maven/Testcontainers suite and explicit synchronous compatibility, Redis failure/reconciliation, command-race, expiration-race, configuration-guard, retry, and dead-letter selections.
- [x] 9.2 Run the pinned live RocketMQ end-to-end and fault matrix and retain append-only reports tied to the tested Git revision and resolved environment.
- [x] 9.3 Run the controlled local characterization and verify all existing committed-state invariant queries and new identity-level reconciliation checks.
- [x] 9.4 Update architecture, transaction-boundary, runbook, scenario-matrix, deferred-roadmap, README, changelog, and verification-status documents with exact V3 behavior and the direct-publication/V4 Outbox boundary.
- [x] 9.5 Add ADRs for explicit live mode, direct-publication acceptance, bounded retry/DLQ, Redis handling of dead-lettered ambiguity, and scanner-backed delayed delivery.
- [x] 9.6 Run strict OpenSpec validation, Compose resolution, dependency/source boundary checks, and `git diff --check`.
- [ ] 9.7 Rerun every required V3 gate against one clean attributable implementation revision and record each result as `PASS`, `FAIL`, `BLOCKED`, or `NOT_RUN` before claiming V3 complete.
