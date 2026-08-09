# V2.1 messaging-readiness runbook

## Normal runtime and rollback

Leave `FLASHFLOW_MESSAGING_MODE=DISABLED` (the default). The application fails startup if `SPIKE` is selected without the `messaging-spike` profile, or if that profile is active without `SPIKE`. Rollback is configuration-only: stop the isolated Compose profile and return to `DISABLED`; synchronous `/api/v1/orders` is unchanged.

## Inspect a command

Query `order_command_ledger` by the privacy-preserving `command_id`. Compare `payload_fingerprint`, `status`, `attempt_count`, `result_code`, `order_id`, and timestamps. Never copy raw caller IDs or idempotency keys into reports or metric tags. `UNRESOLVED` and ambiguous publication require reconciliation; do not release admission capacity merely because a client timed out.

## Reproduce the isolated topology probe

Prerequisites are Docker Desktop/Compose and ports 9876, 10909, 10911, and 8081. Run:

```bash
FLASHFLOW_DOCKER_CONTEXT=desktop-linux scripts/run-rocketmq-spike.sh
```

The script starts only the `messaging-spike` profile, waits for `mqadmin clusterList`, captures resolved Compose and image identity below an append-only timestamped `reports/messaging/` directory, records `PASS` or `BLOCKED`, and tears the topology down. Override the Docker context when required. Inspect `cluster-error.log` and Compose logs for a blocked run.

## Result interpretation

- `PASS`: the named command executed and its assertions passed.
- `FAIL`: the command executed and contradicted an assertion.
- `BLOCKED`: required infrastructure or a pinned input was unavailable.
- `NOT_RUN`: an available command was not executed.

Synthetic harness results demonstrate deterministic application decisions only. They do not prove Broker redelivery, persistence, failover, delay SLA, throughput, or production readiness. A V3 readiness claim requires synchronous compatibility, Redis reconciliation, command/expiration races, actual pinned RocketMQ evidence, strict OpenSpec validation, and a clean attributable revision all passing together.
