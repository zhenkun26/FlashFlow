#!/usr/bin/env bash
set -euo pipefail

matrix_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
matrix_root="$(cd "$matrix_script_dir/.." && pwd -P)"
cd "$matrix_root"

matrix_manifest="${FLASHFLOW_EXPERIMENT_MANIFEST:-$matrix_root/experiments/matrix.json}"
matrix_maven_repo="${FLASHFLOW_EXPERIMENT_MAVEN_REPO:-/private/tmp/flashflow-m2}"
matrix_gate_log="$(mktemp "${TMPDIR:-/tmp}/flashflow-correctness-gate.XXXXXX.log")"
matrix_case_list="$(mktemp "${TMPDIR:-/tmp}/flashflow-experiment-cases.XXXXXX.txt")"
trap 'rm -f "$matrix_gate_log" "$matrix_case_list"' EXIT

mvn -o -Dmaven.repo.local="$matrix_maven_repo" test > "$matrix_gate_log" 2>&1
mvn -q -o -Dmaven.repo.local="$matrix_maven_repo" -DskipTests \
  -Dexec.mainClass=dev.flashflow.verification.experiment.ExperimentManifestCli \
  -Dexec.args="list $matrix_manifest" exec:java > "$matrix_case_list"

matrix_failed=false
matrix_expected_cases="$(wc -l < "$matrix_case_list" | tr -d ' ')"
matrix_executed_cases=0
while IFS= read -r matrix_case_id; do
  [[ -n "$matrix_case_id" ]] || continue
  matrix_executed_cases=$((matrix_executed_cases + 1))
  if ! CORRECTNESS_GATE_STATUS=PASS CORRECTNESS_GATE_REFERENCE="$matrix_gate_log" \
    "$matrix_script_dir/run-experiment.sh" "$matrix_case_id" < /dev/null; then
    matrix_failed=true
  fi
done < "$matrix_case_list"

[[ "$matrix_executed_cases" == "$matrix_expected_cases" ]]
[[ "$matrix_failed" == false ]]
