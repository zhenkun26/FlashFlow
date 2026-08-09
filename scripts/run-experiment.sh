#!/usr/bin/env bash
set -euo pipefail

experiment_case_id="${1:-}"
if [[ -z "$experiment_case_id" ]]; then
  echo "Usage: $0 <case-id>" >&2
  exit 2
fi

experiment_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
experiment_root="$(cd "$experiment_script_dir/.." && pwd -P)"
cd "$experiment_root"

experiment_manifest="${FLASHFLOW_EXPERIMENT_MANIFEST:-$experiment_root/experiments/matrix.json}"
experiment_runs_root="${FLASHFLOW_EXPERIMENT_RUNS_ROOT:-$experiment_root/experiments/runs}"
experiment_maven_repo="${FLASHFLOW_EXPERIMENT_MAVEN_REPO:-/private/tmp/flashflow-m2}"
experiment_app_port="${FLASHFLOW_EXPERIMENT_APP_PORT:-8080}"
experiment_mysql_port="${FLASHFLOW_MYSQL_PORT:-3306}"
experiment_uuid="$(uuidgen | tr '[:upper:]' '[:lower:]')"
experiment_run_id="$(date -u +%Y%m%dT%H%M%SZ)-${experiment_case_id}-${experiment_uuid}"
experiment_run_dir="$experiment_runs_root/$experiment_run_id"
mkdir -p "$experiment_run_dir"

experiment_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
experiment_git_revision="$(git rev-parse HEAD 2>/dev/null || printf UNKNOWN)"
experiment_dirty=false
if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then experiment_dirty=true; fi

{
  printf 'runId=%s\n' "$experiment_run_id"
  printf 'caseId=%s\n' "$experiment_case_id"
  printf 'startedAt=%s\n' "$experiment_started_at"
  printf 'gitRevision=%s\n' "$experiment_git_revision"
  printf 'dirtyWorktree=%s\n' "$experiment_dirty"
  printf 'correctnessGate=NOT_RUN\n'
  printf 'correctnessGateReference=none\n'
  printf 'workloadCompleted=false\n'
} > "$experiment_run_dir/metadata.properties"

experiment_app_pid=""
experiment_cleanup() {
  if [[ -n "$experiment_app_pid" ]] && kill -0 "$experiment_app_pid" 2>/dev/null; then
    kill "$experiment_app_pid" 2>/dev/null || true
    wait "$experiment_app_pid" 2>/dev/null || true
  fi
}
trap experiment_cleanup EXIT

experiment_blocked() {
  local reason="$1"
  printf 'correctnessGate=BLOCKED\nendedAt=%s\nworkloadCompleted=false\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$experiment_run_dir/metadata.properties"
  printf '# FlashFlow experiment %s\n\nStatus: **BLOCKED**\n\n%s\n' "$experiment_run_id" "$reason" > "$experiment_run_dir/report.md"
  echo "BLOCKED: $reason" >&2
  echo "$experiment_run_dir"
  exit 3
}

for experiment_command in mvn docker k6 curl git lsof uuidgen; do
  command -v "$experiment_command" >/dev/null 2>&1 || experiment_blocked "required command is unavailable: $experiment_command"
done
mvn -version >/dev/null 2>&1 || experiment_blocked "Maven cannot locate a working Java runtime"
docker compose version >/dev/null 2>&1 || experiment_blocked "Docker Compose is unavailable"

if lsof -nP -iTCP:"$experiment_app_port" -sTCP:LISTEN >/dev/null 2>&1; then
  experiment_blocked "application port $experiment_app_port is already owned; choose FLASHFLOW_EXPERIMENT_APP_PORT"
fi

cp "$experiment_manifest" "$experiment_run_dir/manifest.json"
if ! mvn -q -o -Dmaven.repo.local="$experiment_maven_repo" -DskipTests \
  -Dexec.mainClass=dev.flashflow.verification.experiment.ExperimentManifestCli \
  -Dexec.args="resolve $experiment_manifest $experiment_case_id" exec:java \
  > "$experiment_run_dir/resolved.env" 2> "$experiment_run_dir/manifest-validation.log"; then
  experiment_blocked "manifest validation or case resolution failed"
fi

while IFS='=' read -r experiment_key experiment_value; do
  case "$experiment_key" in
    CASE_ID) resolved_case_id="$experiment_value" ;;
    PROFILE) resolved_profile="$experiment_value" ;;
    STRATEGY) resolved_strategy="$experiment_value" ;;
    VUS) resolved_vus="$experiment_value" ;;
    DURATION_SECONDS) resolved_duration="$experiment_value" ;;
    INITIAL_STOCK) resolved_stock="$experiment_value" ;;
    SKU_DISTRIBUTION) resolved_sku_distribution="$experiment_value" ;;
    SKU_COUNT) resolved_sku_count="$experiment_value" ;;
    POOL_SIZE) resolved_pool_size="$experiment_value" ;;
    CONNECTION_TIMEOUT_MS) resolved_connection_timeout="$experiment_value" ;;
    OPTIMISTIC_MAX_RETRIES) resolved_optimistic_retries="$experiment_value" ;;
    TRANSACTION_MAX_RETRIES) resolved_transaction_retries="$experiment_value" ;;
    TRANSACTION_SEQUENCE) resolved_transaction_sequence="$experiment_value" ;;
  esac
done < "$experiment_run_dir/resolved.env"

if [[ "${resolved_case_id:-}" != "$experiment_case_id" ]]; then
  experiment_blocked "resolved manifest output is incomplete"
fi

experiment_gate_status="${CORRECTNESS_GATE_STATUS:-}"
experiment_gate_reference="${CORRECTNESS_GATE_REFERENCE:-}"
if [[ "$experiment_gate_status" == "PASS" && -f "$experiment_gate_reference" ]]; then
  cp "$experiment_gate_reference" "$experiment_run_dir/correctness-gate.log"
else
  if mvn -o -Dmaven.repo.local="$experiment_maven_repo" test > "$experiment_run_dir/correctness-gate.log" 2>&1; then
    experiment_gate_status=PASS
  else
    experiment_gate_status=FAIL
  fi
fi
printf 'correctnessGate=%s\ncorrectnessGateReference=correctness-gate.log\n' "$experiment_gate_status" >> "$experiment_run_dir/metadata.properties"
if [[ "$experiment_gate_status" != "PASS" ]]; then
  printf 'endedAt=%s\nworkloadCompleted=false\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$experiment_run_dir/metadata.properties"
  printf '# FlashFlow experiment %s\n\nStatus: **FAIL**\n\nThe correctness gate failed; characterization was not executed.\n' "$experiment_run_id" > "$experiment_run_dir/report.md"
  echo "$experiment_run_dir"
  exit 1
fi

FLASHFLOW_MYSQL_PORT="$experiment_mysql_port" docker compose up -d mysql \
  > "$experiment_run_dir/docker-compose.log" 2>&1 || experiment_blocked "Compose MySQL failed to start; use a safe port override if needed"

experiment_mysql_healthy=false
for ((experiment_check=0; experiment_check<120; experiment_check++)); do
  if docker compose exec -T mysql mysqladmin ping -h 127.0.0.1 -uflashflow -pflashflow \
      >/dev/null 2>&1; then
    experiment_mysql_healthy=true
    break
  fi
  sleep 0.5
done
[[ "$experiment_mysql_healthy" == true ]] || experiment_blocked "Compose MySQL did not become healthy"

experiment_java_version="$(mvn -version | awk -F': ' '/Java version/ {print $2; exit}' | tr '=' '-')"
experiment_docker_version="$(docker version --format '{{.Server.Version}}' 2>/dev/null || printf UNKNOWN)"
experiment_mysql_version="$(docker compose exec -T mysql mysql -N -B -uflashflow -pflashflow -e 'SELECT VERSION()' 2>/dev/null || printf UNKNOWN)"
{
  printf 'environment.java=%s\n' "$experiment_java_version"
  printf 'environment.docker=%s\n' "$experiment_docker_version"
  printf 'environment.mysql=%s\n' "$experiment_mysql_version"
  printf 'environment.os=%s\n' "$(uname -srm | tr '=' '-')"
} >> "$experiment_run_dir/metadata.properties"

SPRING_PROFILES_ACTIVE="$resolved_profile" \
SERVER_PORT="$experiment_app_port" \
FLASHFLOW_DB_URL="jdbc:mysql://127.0.0.1:$experiment_mysql_port/flashflow?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC" \
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE="$resolved_pool_size" \
SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT="$resolved_connection_timeout" \
FLASHFLOW_INVENTORY_STRATEGY="$resolved_strategy" \
FLASHFLOW_INVENTORY_OPTIMISTIC_MAX_RETRIES="$resolved_optimistic_retries" \
FLASHFLOW_ORDERING_TRANSACTION_MAX_RETRIES="$resolved_transaction_retries" \
FLASHFLOW_ORDERING_TRANSACTION_SEQUENCE="$resolved_transaction_sequence" \
mvn -q -o -Dmaven.repo.local="$experiment_maven_repo" \
  -Dexec.mainClass=dev.flashflow.FlashFlowApplication exec:java \
  > "$experiment_run_dir/application.log" 2>&1 &
experiment_app_pid=$!
printf '%s\n' "$experiment_app_pid" > "$experiment_run_dir/application.pid"

experiment_healthy=false
for ((experiment_check=0; experiment_check<120; experiment_check++)); do
  if curl -fsS "http://127.0.0.1:$experiment_app_port/actuator/health" >/dev/null 2>&1; then
    experiment_healthy=true
    break
  fi
  if ! kill -0 "$experiment_app_pid" 2>/dev/null; then break; fi
  sleep 0.5
done
[[ "$experiment_healthy" == true ]] || experiment_blocked "application did not become healthy; inspect application.log"

"$experiment_script_dir/prepare-experiment-data.sh" "$resolved_stock" "$resolved_sku_count" \
  > "$experiment_run_dir/data-preparation.log" 2>&1 || experiment_blocked "disposable dataset preparation failed"

experiment_workload_completed=false
if BASE_URL="http://127.0.0.1:$experiment_app_port" CASE_ID="$resolved_case_id" VUS="$resolved_vus" \
  DURATION="${resolved_duration}s" SKU_DISTRIBUTION="$resolved_sku_distribution" SKU_COUNT="$resolved_sku_count" \
  K6_SUMMARY_PATH="$experiment_run_dir/k6-summary.json" \
  k6 run "$experiment_root/load-tests/synchronous-orders.js" > "$experiment_run_dir/k6.log" 2>&1; then
  experiment_workload_completed=true
fi

curl -fsS "http://127.0.0.1:$experiment_app_port/actuator/prometheus" > "$experiment_run_dir/metrics.prom" \
  || rm -f "$experiment_run_dir/metrics.prom"
docker compose exec -T mysql mysql -B -uflashflow -pflashflow flashflow \
  < "$experiment_script_dir/experiment-invariants.sql" > "$experiment_run_dir/invariants.tsv" \
  || rm -f "$experiment_run_dir/invariants.tsv"

if [[ "${CAPTURE_MYSQL_DIAGNOSTICS:-false}" == "true" ]]; then
  {
    printf 'captured_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    docker compose exec -T -e MYSQL_PWD=root mysql mysql -uroot -e \
      "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'; SELECT ENGINE_TRANSACTION_ID,OBJECT_NAME,INDEX_NAME,LOCK_TYPE,LOCK_MODE,LOCK_DATA FROM performance_schema.data_locks WHERE OBJECT_SCHEMA='flashflow'; SHOW ENGINE INNODB STATUS;"
  } > "$experiment_run_dir/mysql-diagnostics.txt" 2> "$experiment_run_dir/mysql-diagnostics-error.log" || true
fi

printf 'endedAt=%s\nworkloadCompleted=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$experiment_workload_completed" \
  >> "$experiment_run_dir/metadata.properties"

experiment_report_status="$(mvn -q -o -Dmaven.repo.local="$experiment_maven_repo" -DskipTests \
  -Dexec.mainClass=dev.flashflow.verification.experiment.ExperimentEvidenceCli \
  -Dexec.args="report $experiment_run_dir" exec:java 2> "$experiment_run_dir/report-error.log" || printf FAIL)"
echo "$experiment_run_dir"
[[ "$experiment_report_status" == *PASS* ]]
