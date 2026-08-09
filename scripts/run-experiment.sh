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
experiment_redis_port="${FLASHFLOW_REDIS_PORT:-6379}"
experiment_uuid="$(uuidgen | tr '[:upper:]' '[:lower:]')"
experiment_compose_project="flashflowexp${experiment_uuid//-/}"
experiment_mysql_data_dir="$(mktemp -d "${TMPDIR:-/tmp}/flashflow-experiment-mysql.XXXXXX")"
export COMPOSE_PROJECT_NAME="$experiment_compose_project"
export FLASHFLOW_MYSQL_DATA_DIR="$experiment_mysql_data_dir"
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
experiment_compose_started=false
experiment_cleanup() {
  if [[ -n "$experiment_app_pid" ]] && kill -0 "$experiment_app_pid" 2>/dev/null; then
    kill "$experiment_app_pid" 2>/dev/null || true
    wait "$experiment_app_pid" 2>/dev/null || true
  fi
  if [[ "$experiment_compose_started" == true ]]; then
    docker compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
  case "$experiment_mysql_data_dir" in
    "${TMPDIR:-/tmp}"/flashflow-experiment-mysql.*) rm -rf -- "$experiment_mysql_data_dir" ;;
  esac
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
    ADMISSION_MODE) resolved_admission_mode="$experiment_value" ;;
    HELD_RESOLUTION_SECONDS) resolved_held_resolution="$experiment_value" ;;
    REDIS_IMAGE) resolved_redis_image="$experiment_value" ;;
    SCRIPT_VERSION) resolved_script_version="$experiment_value" ;;
    GENERATION) resolved_generation="$experiment_value" ;;
    INJECTED_FAILURE) resolved_injected_failure="$experiment_value" ;;
  esac
done < "$experiment_run_dir/resolved.env"

if [[ "${resolved_case_id:-}" != "$experiment_case_id" ]]; then
  experiment_blocked "resolved manifest output is incomplete"
fi
if [[ "$resolved_injected_failure" != "NONE" ]]; then
  experiment_blocked "injected failure $resolved_injected_failure must use the deterministic fault-drill suite"
fi
resolved_redis_health=false
if [[ "$resolved_admission_mode" == "REDIS_LUA" ]]; then resolved_redis_health=true; fi

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

experiment_services=(mysql)
if [[ "$resolved_admission_mode" == "REDIS_LUA" ]]; then experiment_services+=(redis); fi
experiment_compose_started=true
FLASHFLOW_MYSQL_PORT="$experiment_mysql_port" FLASHFLOW_REDIS_PORT="$experiment_redis_port" \
  docker compose up -d "${experiment_services[@]}" \
  > "$experiment_run_dir/docker-compose.log" 2>&1 || experiment_blocked "Compose dependencies failed to start; use safe port overrides if needed"

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

if [[ "$resolved_admission_mode" == "REDIS_LUA" ]]; then
  experiment_redis_healthy=false
  for ((experiment_check=0; experiment_check<120; experiment_check++)); do
    if docker compose exec -T redis redis-cli ping 2>/dev/null | grep -q PONG; then
      experiment_redis_healthy=true
      break
    fi
    sleep 0.5
  done
  [[ "$experiment_redis_healthy" == true ]] || experiment_blocked "Compose Redis did not become healthy"
fi

experiment_java_version="$(mvn -version | awk -F': ' '/Java version/ {print $2; exit}' | tr '=' '-')"
experiment_docker_version="$(docker version --format '{{.Server.Version}}' 2>/dev/null || printf UNKNOWN)"
experiment_mysql_version="$(docker compose exec -T mysql mysql -N -B -uflashflow -pflashflow -e 'SELECT VERSION()' 2>/dev/null || printf UNKNOWN)"
{
  printf 'environment.java=%s\n' "$experiment_java_version"
  printf 'environment.docker=%s\n' "$experiment_docker_version"
  printf 'environment.mysql=%s\n' "$experiment_mysql_version"
  printf 'environment.admissionMode=%s\n' "$resolved_admission_mode"
  printf 'environment.redisImage=%s\n' "$resolved_redis_image"
  printf 'environment.scriptVersion=%s\n' "$resolved_script_version"
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
FLASHFLOW_ADMISSION_MODE="$resolved_admission_mode" \
FLASHFLOW_ADMISSION_HELD_RESOLUTION="${resolved_held_resolution}s" \
FLASHFLOW_ADMISSION_SCRIPT_VERSION="$resolved_script_version" \
FLASHFLOW_ADMISSION_GENERATION="$resolved_generation" \
FLASHFLOW_ADMISSION_IDENTITY_SECRET="flashflow-local-experiment-identity-secret-32-chars" \
FLASHFLOW_REDIS_URL="redis://127.0.0.1:$experiment_redis_port" \
FLASHFLOW_REDIS_HEALTH_ENABLED="$resolved_redis_health" \
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

if [[ "$resolved_admission_mode" == "REDIS_LUA" ]]; then
  mkdir -p "$experiment_run_dir/reconciliation-initial"
  SPRING_PROFILES_ACTIVE="$resolved_profile" \
  FLASHFLOW_DB_URL="jdbc:mysql://127.0.0.1:$experiment_mysql_port/flashflow?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC" \
  FLASHFLOW_ADMISSION_MODE=REDIS_LUA \
  FLASHFLOW_ADMISSION_HELD_RESOLUTION="${resolved_held_resolution}s" \
  FLASHFLOW_ADMISSION_SCRIPT_VERSION="$resolved_script_version" \
  FLASHFLOW_ADMISSION_GENERATION="$resolved_generation" \
  FLASHFLOW_ADMISSION_IDENTITY_SECRET="flashflow-local-experiment-identity-secret-32-chars" \
  FLASHFLOW_REDIS_URL="redis://127.0.0.1:$experiment_redis_port" \
  mvn -q -o -Dmaven.repo.local="$experiment_maven_repo" -DskipTests \
    -Dexec.mainClass=dev.flashflow.verification.experiment.AdmissionExperimentCli \
    -Dexec.args="$experiment_run_dir/reconciliation-initial $resolved_sku_count" exec:java \
    > "$experiment_run_dir/admission-initial.properties" 2> "$experiment_run_dir/admission-initial.log" \
    || experiment_blocked "Redis admission generation initialization failed"
fi

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

if [[ "$resolved_admission_mode" == "REDIS_LUA" ]]; then
  mkdir -p "$experiment_run_dir/reconciliation-final"
  SPRING_PROFILES_ACTIVE="$resolved_profile" \
  FLASHFLOW_DB_URL="jdbc:mysql://127.0.0.1:$experiment_mysql_port/flashflow?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC" \
  FLASHFLOW_ADMISSION_MODE=REDIS_LUA \
  FLASHFLOW_ADMISSION_HELD_RESOLUTION="${resolved_held_resolution}s" \
  FLASHFLOW_ADMISSION_SCRIPT_VERSION="$resolved_script_version" \
  FLASHFLOW_ADMISSION_GENERATION="$resolved_generation" \
  FLASHFLOW_ADMISSION_IDENTITY_SECRET="flashflow-local-experiment-identity-secret-32-chars" \
  FLASHFLOW_REDIS_URL="redis://127.0.0.1:$experiment_redis_port" \
  mvn -q -o -Dmaven.repo.local="$experiment_maven_repo" -DskipTests \
    -Dexec.mainClass=dev.flashflow.verification.experiment.AdmissionExperimentCli \
    -Dexec.args="$experiment_run_dir/reconciliation-final $resolved_sku_count" exec:java \
    > "$experiment_run_dir/admission-evidence.properties" 2> "$experiment_run_dir/admission-final.log" \
    || experiment_blocked "final Redis reconciliation failed"
  {
    docker compose exec -T redis redis-cli INFO memory
    docker compose exec -T redis redis-cli --scan --pattern 'flashflow:v2:*'
  } > "$experiment_run_dir/redis-evidence.txt" 2>&1 || experiment_blocked "Redis evidence capture failed"
fi

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
