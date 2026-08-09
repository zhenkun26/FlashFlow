# V2.1 messaging-readiness architecture

V2.1 freezes the correctness contracts that V3 must preserve. It does not turn synchronous ordering into a broker-dependent runtime.

## Boundaries

The default `DISABLED` messaging mode exposes no `/api/v2/orders` route and loads no RocketMQ client. `SPIKE` is accepted only with the isolated `messaging-spike` profile. MySQL remains authoritative and Redis remains a fail-closed admission layer.

## Command lifecycle

`OrderCommandEnvelope` version 1 carries a stable privacy-preserving command ID, caller/SKU inputs, payload fingerprint, creation time, and bounded correlation. The command ledger supports create/replay, claim, completion, rejection, retryable, and unresolved states. It does not contain a broker payload or publication queue and has no dispatcher, so it is not a Transactional Outbox.

An idempotent consumer claims the stable identity, validates the fingerprint/version, invokes the existing ordering boundary, and records only its durable result. Acknowledgement is eligible only after that result is recoverable or the input is deterministically non-retryable. Duplicate delivery and commit-before-ack interruption therefore replay one result.

## Admission and publication

The future sequence is acquire admission, attempt publication, then classify the result. Definitively-not-published may release once when no MySQL attempt can result. Broker acknowledgement retains capacity until a durable consumer outcome. Timeout or lost reply is ambiguous and quarantines capacity; retry reuses the same command ID.

The future HTTP contract separates `202 Accepted` from completion and exposes bounded command status. Both routes remain disabled in V2.1.

## Expiration

Delayed triggers and the database scanner call the same locked order-specific close operation. It rechecks `expiresAt`, payment state, and terminal state before atomic reservation release, stock movement, and claim removal. The scanner remains the V3 recovery path when a delayed message is missing.

## RocketMQ compatibility scope

The spike pins Apache RocketMQ 5.3.4 NameServer/Broker containers and enables the broker Proxy only inside the Compose profile. Transport-neutral synthetic probes cover duplicate/redelivery, poison versions, lost producer responses, consumer interruption, acknowledgement loss, restart classification, and delay observations. Those probes validate FlashFlow decisions; only retained Broker execution evidence can establish compatibility with the pinned topology.
