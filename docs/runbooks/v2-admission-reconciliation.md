# V2 admission operations runbook

## Enable Redis admission locally

Use the pinned Compose Redis and a secret of at least 32 characters:

```bash
docker compose up -d mysql redis
export FLASHFLOW_ADMISSION_MODE=REDIS_LUA
export FLASHFLOW_ADMISSION_IDENTITY_SECRET='replace-with-a-local-secret-at-least-32-chars'
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Relevant settings are `FLASHFLOW_REDIS_URL`, `FLASHFLOW_REDIS_CONNECT_TIMEOUT`, `FLASHFLOW_REDIS_COMMAND_TIMEOUT`, `FLASHFLOW_ADMISSION_HELD_RESOLUTION`, and `FLASHFLOW_ADMISSION_SCRIPT_VERSION`. Unsafe or incomplete Redis-mode configuration fails startup; it never selects MySQL-only implicitly after a Redis error.

## Initialize or reconcile experiment SKUs

After the disposable MySQL dataset exists, run the fenced reconciler. The second argument is the number of `experiment-sku-N` rows:

```bash
mkdir -p experiments/manual-reconciliation
mvn -q -o -Dmaven.repo.local=/private/tmp/flashflow-m2 -DskipTests \
  -Dexec.mainClass=dev.flashflow.verification.experiment.AdmissionExperimentCli \
  -Dexec.args='experiments/manual-reconciliation 1' exec:java
```

Each run creates a new JSON report and refuses to overwrite it. `PASS` means a complete replacement generation was published. `BLOCKED` means MySQL/Redis evidence, fence ownership, or held-token resolution was insufficient; capacity remains unavailable and the run must be investigated and retried after the lease expires. Never delete or increment individual admission records to force recovery.

## Explicit rollback to V1 behavior

1. Stop incoming traffic or restart the application through the normal local procedure.
2. Set `FLASHFLOW_ADMISSION_MODE=MYSQL_ONLY` explicitly.
3. Restart and verify the selected mode in resolved configuration/experiment evidence.
4. Keep Redis evidence and reconciliation reports; Redis data is no longer on the request path and no MySQL migration is needed.

This rollback restores the V1 synchronous MySQL path. It does not merge Redis token counts into durable inventory and does not authorize deleting Docker volumes.
