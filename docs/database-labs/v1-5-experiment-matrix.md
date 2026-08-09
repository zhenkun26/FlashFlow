# V1.5 experiment matrix reproduction

The V1.5 tooling runs disposable local characterizations only. It deletes data from the resolved FlashFlow Compose database after verifying that the MySQL container belongs to this workspace; do not point it at a persistent or unresolved database.

## Prerequisites

- Maven with a working Java runtime, Docker Compose, k6, curl, Git, lsof, and uuidgen.
- Free application and MySQL host ports.
- A Docker runtime available to Maven Testcontainers and Compose.

The runner executes the complete Maven correctness gate before characterization. Missing infrastructure becomes `BLOCKED`; an executed failing gate or invariant becomes `FAIL`.

## Run one case

From the repository root:

```bash
FLASHFLOW_MYSQL_PORT=3307 \
FLASHFLOW_EXPERIMENT_APP_PORT=8081 \
CAPTURE_MYSQL_DIAGNOSTICS=true \
./scripts/run-experiment.sh baseline
```

Omit either port override when its default (MySQL 3306, application 8080) is free. The runner refuses an occupied application port and Compose fails safely if the requested MySQL port cannot be bound. It only terminates the application process that it started; it does not stop unrelated host processes.

Available case identifiers are defined in `experiments/matrix.json`. The unsafe strategy is accepted only with the `lab` profile.

## Run a controlled comparison

Run the two named cases independently, for example the strategy pair:

```bash
FLASHFLOW_MYSQL_PORT=3307 ./scripts/run-experiment.sh baseline
FLASHFLOW_MYSQL_PORT=3307 ./scripts/run-experiment.sh strategy-pessimistic
```

Or execute the complete canonical matrix:

```bash
FLASHFLOW_MYSQL_PORT=3307 CAPTURE_MYSQL_DIAGNOSTICS=true \
./scripts/run-experiment-matrix.sh
```

The matrix validator requires exactly one declared factor to change in every comparison. The matrix runner also fails unless its executed-case count equals its planned-case count.

## Evidence layout

Each attempt creates a collision-resistant directory under `experiments/runs/<run-id>/`. A completed run includes:

- `manifest.json` and `resolved.env`;
- `metadata.properties` with revision, dirty state, timestamps, environment, and correctness gate;
- `correctness-gate.log`, `application.log`, `k6.log`, and `k6-summary.json`;
- `metrics.prom`, `invariants.tsv`, `evidence.json`, and `report.md`;
- optional `mysql-diagnostics.txt` and `mysql-diagnostics-error.log`.

Run directories and reports are append-only: the reporter refuses to overwrite an existing `evidence.json` or `report.md`. Transient raw runs are ignored by Git; deliberately curate a dated report under `docs/verification/` when evidence should be retained in repository history.

Always compare created results, each business rejection, retry exhaustion, unexpected failures, retry/conflict and pool metrics, latency, and committed-state invariants separately. A local request rate is not production QPS, availability, or capacity evidence.
