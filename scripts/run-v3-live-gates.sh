#!/usr/bin/env bash
set -euo pipefail

run_id="$(date -u +%Y%m%dT%H%M%SZ)-v3-live"
report_dir="${1:-reports/messaging}/${run_id}"
docker_context="${FLASHFLOW_DOCKER_CONTEXT:-desktop-linux}"
docker_cmd=(docker --context "${docker_context}")

if [[ -e "${report_dir}" ]]; then
  echo "Refusing to overwrite existing report: ${report_dir}" >&2
  exit 2
fi
mkdir -p "${report_dir}"

revision="$(git rev-parse HEAD)"
dirty="false"
if [[ -n "$(git status --short)" ]]; then dirty="true"; fi
started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

finish() {
  local exit_code=$?
  local status="PASS"
  if [[ ${exit_code} -ne 0 ]]; then status="FAIL"; fi
  {
    echo "status=${status}"
    echo "endedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } >>"${report_dir}/metadata.properties"
}
trap finish EXIT

{
  echo "runId=${run_id}"
  echo "startedAt=${started_at}"
  echo "gitRevision=${revision}"
  echo "dirtyWorktree=${dirty}"
  echo "rocketmqClient=5.3.3"
  echo "rocketmqBroker=5.3.4"
  echo "acknowledgement=SYNC_FLUSH"
  echo "namesrv=127.0.0.1:${FLASHFLOW_ROCKETMQ_NAMESRV_PORT:-9876}"
  echo "orderTopic=flashflow-order-command-v1"
  echo "expirationTopic=flashflow-expiration-v1"
  echo "deadLetterTopic=flashflow-order-dead-letter-v1"
} >"${report_dir}/metadata.properties"

"${docker_cmd[@]}" version >"${report_dir}/docker-version.txt"
"${docker_cmd[@]}" compose --profile messaging-live config \
  | sed -E 's/^([[:space:]]*MYSQL(_ROOT)?_PASSWORD:).*/\1 <redacted-local-default>/' \
  >"${report_dir}/compose-resolved.yaml"
"${docker_cmd[@]}" compose --profile messaging-live up -d \
  rocketmq-namesrv rocketmq-store-init rocketmq-broker rocketmq-topics
"${docker_cmd[@]}" wait flashflow-rocketmq-topics-1 >"${report_dir}/topic-provision-exit.txt"
"${docker_cmd[@]}" compose --profile messaging-live ps -a >"${report_dir}/compose-ps.txt"
"${docker_cmd[@]}" compose --profile messaging-live logs --no-color \
  rocketmq-broker rocketmq-topics >"${report_dir}/broker-and-topic.log"

mvn -Dtest=LiveRocketMqEndToEndIntegrationTest,LiveRocketMqScannerRecoveryIntegrationTest,LiveRocketMqRedeliveryIntegrationTest,LiveRocketMqRetryExhaustionIntegrationTest,LiveRocketMqExpirationAckLossIntegrationTest,V3ControlledComparisonIntegrationTest test \
  2>&1 | tee "${report_dir}/live-gate.log"
mvn -Dtest=AsyncOrderApplicationServiceTest,DeterministicPublicationCoordinatorTest,RocketMqListenerContractTest,OrderCommandConsumerIntegrationTest,DelayedExpirationIntegrationTest,AdmissionReconciliationIntegrationTest,AdmissionReconciliationFailureTest test \
  2>&1 | tee "${report_dir}/deterministic-gate.log"

echo "${report_dir}"
