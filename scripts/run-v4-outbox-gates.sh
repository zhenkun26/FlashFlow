#!/usr/bin/env bash
set -euo pipefail

run_id="$(date -u +%Y%m%dT%H%M%SZ)-v4-outbox"
report_dir="${1:-reports/messaging/${run_id}}"
docker_context="${FLASHFLOW_DOCKER_CONTEXT:-desktop-linux}"
docker_cmd=(docker --context "${docker_context}")
profile="messaging-live"

if [[ -e "${report_dir}" ]]; then
  echo "Refusing to overwrite existing report: ${report_dir}" >&2
  exit 2
fi

revision="$(git rev-parse HEAD)"
dirty="false"
if [[ -n "$(git status --short)" ]]; then dirty="true"; fi
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
started_epoch="$(date +%s)"
maven_args=()
if [[ -n "${FLASHFLOW_MAVEN_ARGLINE:-}" ]]; then
  maven_args+=("-DargLine=${FLASHFLOW_MAVEN_ARGLINE}")
fi
mkdir -p "${report_dir}"

finish() {
  local exit_code=$?
  local gate_status="PASS"
  local release_status="PASS"
  if [[ ${exit_code} -ne 0 ]]; then
    gate_status="FAIL"
    release_status="FAIL"
  elif [[ "${dirty}" == "true" ]]; then
    release_status="BLOCKED"
  fi
  {
    echo "gateStatus=${gate_status}"
    echo "releaseStatus=${release_status}"
    echo "status=${release_status}"
    echo "durationSeconds=$(($(date +%s) - started_epoch))"
    echo "endedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } >>"${report_dir}/metadata.properties"
}
trap finish EXIT

{
  echo "runId=${run_id}"
  echo "startedAt=${started_at}"
  echo "gitRevision=${revision}"
  echo "dirtyWorktree=${dirty}"
  echo "machine=$(uname -m)"
  echo "operatingSystem=$(uname -s)"
  echo "dockerContext=${docker_context}"
  echo "rocketmqClient=5.3.3"
  echo "rocketmqBroker=5.3.4"
  echo "mysql=8.4.6"
  echo "messagingModes=DIRECT,OUTBOX"
  echo "acknowledgement=SYNC_FLUSH"
  echo "dataset=experiments/matrix.json"
  echo "backlogDatasetCommands=12"
  echo "dispatcherConcurrency=4"
  echo "faultSchedule=producer-ack-loss,consumer-ack-loss,broker-outage,backlog-drain"
  echo "orderTopic=flashflow-order-command-v1"
  echo "expirationTopic=flashflow-expiration-v1"
  echo "deadLetterTopic=flashflow-order-dead-letter-v1"
} >"${report_dir}/metadata.properties"

"${docker_cmd[@]}" version >"${report_dir}/docker-version.txt"
"${docker_cmd[@]}" info --format '{{json .}}' >"${report_dir}/docker-info.json"
"${docker_cmd[@]}" compose --profile "${profile}" config \
  | sed -E 's/^([[:space:]]*MYSQL(_ROOT)?_PASSWORD:).*/\1 <redacted-local-default>/' \
  >"${report_dir}/compose-resolved.yaml"
"${docker_cmd[@]}" compose --profile "${profile}" up -d \
  rocketmq-namesrv rocketmq-store-init rocketmq-broker rocketmq-topics
"${docker_cmd[@]}" wait flashflow-rocketmq-topics-1 >"${report_dir}/topic-provision-exit.txt"
"${docker_cmd[@]}" compose --profile "${profile}" ps -a >"${report_dir}/compose-ps.txt"
"${docker_cmd[@]}" compose --profile "${profile}" logs --no-color \
  rocketmq-broker rocketmq-topics >"${report_dir}/container.log"

mvn "${maven_args[@]}" -Dtest=OutboxAcceptanceIntegrationTest,OutboxPersistenceIntegrationTest,AdmissionReconciliationIntegrationTest test \
  2>&1 | tee "${report_dir}/mysql-outbox-gate.log"
mvn "${maven_args[@]}" -Dflashflow.v4.report-dir="${report_dir}" \
  -Dtest=V4OutboxRocketMqEndToEndIntegrationTest,V4OutboxProducerAckLossIntegrationTest,V4OutboxConcurrentRedeliveryIntegrationTest,V4OutboxBacklogDrainIntegrationTest test \
  2>&1 | tee "${report_dir}/rocketmq-recovery-gate.log"
mvn "${maven_args[@]}" -Dtest=MessagingBoundaryTest,MessagingConfigurationGuardTest,AsyncOrderApplicationServiceTest,OutboxAsyncOrderApplicationServiceTest,OutboxDispatcherTest,DeterministicPublicationCoordinatorTest,RocketMqListenerContractTest,FlashFlowMetricsTest,ExperimentEvidenceReporterTest,ExperimentManifestValidatorTest test \
  2>&1 | tee "${report_dir}/deterministic-gate.log"
mvn "${maven_args[@]}" test 2>&1 | tee "${report_dir}/complete-maven-gate.log"
openspec validate build-v4-transactional-outbox-publication --strict \
  2>&1 | tee "${report_dir}/openspec-validation.log"
git diff --check 2>&1 | tee "${report_dir}/diff-check.log"

echo "${report_dir}"
