# V3 live RocketMQ runbook

## Start

1. Start `mysql`, `redis`, and the `messaging-live` Compose profile.
2. Confirm the one-shot `rocketmq-topics` service exits `0` after creating the order, expiration, and dead-letter topics.
3. Initialize and publish the Redis generation for every active SKU.
4. Set `FLASHFLOW_MESSAGING_MODE=LIVE`, a 32+ character admission secret, and the environment-specific NameServer address.
5. Start the application and verify `/actuator/health` before sending `/api/v2/orders` traffic.

The classic client uses the Broker address advertised through NameServer. Local Docker must advertise a host-reachable address and map the same Broker listen port; a successful mqadmin probe alone does not establish application connectivity.

## Interpret outcomes

- `202 ACCEPTED`: Broker acknowledgement observed; order completion is still pending.
- `RETRYABLE`: no acceptance claim; retry with the same idempotency key.
- `UNRESOLVED`: publication or processing ambiguity; do not release admission blindly.
- `COMPLETED` or `REJECTED`: durable command result is recoverable.
- Dead-letter evidence: bounded transport disposition, not proof of missing MySQL effects.

## Recovery

- Broker unavailable before acceptance: restore Broker and retry the same request identity.
- Lost producer acknowledgement: query status, retry the same identity, and reconcile retained admission from MySQL facts.
- Dead letter: inspect bounded cause and command state; manual replay must preserve command identity.
- Missing delayed trigger: keep the scanner enabled and verify eventual committed closure.

## Verification command

`scripts/run-v3-live-gates.sh` creates a new append-only directory under `reports/messaging/`, records the Git revision and dirty-worktree flag, resolves the Compose topology with local passwords redacted, and runs the real Broker plus deterministic selections. It refuses to overwrite an existing report directory.

The `flashflow.messaging.injected-fault` setting is a verification-only switch and defaults to `NONE`. Retained gates use `AFTER_DURABLE_RESULT_BEFORE_ACK_ONCE`, `BEFORE_CONSUME_ALWAYS`, or `AFTER_EXPIRATION_RESULT_BEFORE_ACK_ONCE` in isolated test application contexts. Do not enable these values for ordinary local traffic.

The broker store uses the dedicated `flashflow_rocketmq-store` Compose volume so topic metadata survives an intentional stop/start drill. This is local test persistence, not a production durability claim. Remove the disposable topology and its broker volume with `docker compose --profile messaging-live down --remove-orphans -v` only after retained evidence has been copied or reviewed.

Rollback is configuration-only: select `DISABLED` and stop the messaging profile. `/api/v1/orders` and committed MySQL data require no migration rollback.
