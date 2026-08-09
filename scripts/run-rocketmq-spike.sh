#!/usr/bin/env bash
set -euo pipefail

run_id="$(date -u +%Y%m%dT%H%M%SZ)-rocketmq-spike"
report_dir="${1:-reports/messaging}/${run_id}"
docker_context="${FLASHFLOW_DOCKER_CONTEXT:-desktop-linux}"
docker_cmd=(docker --context "${docker_context}")
mkdir -p "${report_dir}"

cleanup() {
  "${docker_cmd[@]}" compose --profile messaging-spike down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

"${docker_cmd[@]}" compose --profile messaging-spike up -d rocketmq-namesrv rocketmq-broker
for attempt in $(seq 1 30); do
  "${docker_cmd[@]}" compose --profile messaging-spike exec -T rocketmq-broker \
      sh mqadmin clusterList -n rocketmq-namesrv:9876 >"${report_dir}/cluster.txt" 2>"${report_dir}/cluster-error.log" || true
  if grep -q 'flashflow-broker' "${report_dir}/cluster.txt"; then
    break
  fi
  if [[ "${attempt}" == "30" ]]; then
    printf 'BLOCKED\n' >"${report_dir}/status.txt"
    exit 2
  fi
  sleep 1
done

if ! "${docker_cmd[@]}" compose --profile messaging-spike exec -T rocketmq-broker \
    sh mqadmin updateTopic -n rocketmq-namesrv:9876 -c FlashFlowSpike -t FlashFlowCommandSpike \
    >"${report_dir}/topic-create.txt" 2>"${report_dir}/topic-create-error.log"; then
  printf 'FAIL\n' >"${report_dir}/status.txt"
  exit 3
fi

if ! "${docker_cmd[@]}" compose --profile messaging-spike exec -T rocketmq-broker \
    sh mqadmin sendMessage -n rocketmq-namesrv:9876 -t FlashFlowCommandSpike \
    -k synthetic-command-v1 -p '{"schemaVersion":1,"commandId":"synthetic-command-v1"}' \
    >"${report_dir}/send.txt" 2>"${report_dir}/send-error.log"; then
  printf 'FAIL\n' >"${report_dir}/status.txt"
  exit 4
fi

queue_id="$(awk '$2 ~ /^[0-9]+$/ { print $2; exit }' "${report_dir}/send.txt")"
if [[ -z "${queue_id}" ]]; then
  printf 'FAIL\n' >"${report_dir}/status.txt"
  exit 5
fi

if ! "${docker_cmd[@]}" compose --profile messaging-spike exec -T rocketmq-broker \
    sh mqadmin consumeMessage -n rocketmq-namesrv:9876 -t FlashFlowCommandSpike \
    -g FlashFlowSpikeProbe -b flashflow-broker -i "${queue_id}" -o 0 -c 1 \
    >"${report_dir}/consume.txt" 2>"${report_dir}/consume-error.log"; then
  printf 'FAIL\n' >"${report_dir}/status.txt"
  exit 6
fi

if ! grep -q 'BODY: {"schemaVersion":1,"commandId":"synthetic-command-v1"}' "${report_dir}/consume.txt"; then
  printf 'FAIL\n' >"${report_dir}/status.txt"
  exit 7
fi

"${docker_cmd[@]}" compose --profile messaging-spike config \
  | sed -E 's/^([[:space:]]*MYSQL(_ROOT)?_PASSWORD:).*/\1 <redacted-local-default>/' \
  >"${report_dir}/compose-resolved.yaml"
"${docker_cmd[@]}" compose --profile messaging-spike images >"${report_dir}/images.txt"
"${docker_cmd[@]}" compose --profile messaging-spike exec -T rocketmq-broker \
  sh mqadmin getBrokerConfig -n rocketmq-namesrv:9876 -b rocketmq-broker:10911 \
  2>"${report_dir}/broker-config-error.log" \
  | sed -E -e 's/[[:space:]]+$//' -e '$ {/^[[:space:]]*$/d;}' \
  >"${report_dir}/broker-config.txt" || true
printf 'PASS\n' >"${report_dir}/status.txt"
printf '%s\n' "${report_dir}"
