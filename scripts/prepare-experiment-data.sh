#!/usr/bin/env bash
set -euo pipefail

experiment_stock="${1:-}"
experiment_sku_count="${2:-}"

if [[ ! "$experiment_stock" =~ ^[0-9]+$ ]] || [[ ! "$experiment_sku_count" =~ ^[1-9][0-9]*$ ]]; then
  echo "Usage: $0 <non-negative-total-stock> <positive-sku-count>" >&2
  exit 2
fi

experiment_container_id="$(docker compose ps -q mysql)"
if [[ -z "$experiment_container_id" ]]; then
  echo "BLOCKED: the Compose mysql service is not running" >&2
  exit 3
fi

experiment_working_dir="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project.working_dir" }}' "$experiment_container_id")"
if [[ "$experiment_working_dir" != "$(pwd -P)" ]]; then
  echo "BLOCKED: resolved mysql container does not belong to this workspace" >&2
  exit 3
fi

experiment_sql_path="$(mktemp "${TMPDIR:-/tmp}/flashflow-experiment-data.XXXXXX.sql")"
trap 'rm -f "$experiment_sql_path"' EXIT

{
  printf '%s\n' "DELETE FROM compensation_case;"
  printf '%s\n' "DELETE FROM payment_callback_event;"
  printf '%s\n' "DELETE FROM payment;"
  printf '%s\n' "DELETE FROM inventory_movement;"
  printf '%s\n' "DELETE FROM inventory_reservation;"
  printf '%s\n' "DELETE FROM purchase_claim;"
  printf '%s\n' "DELETE FROM idempotency_record;"
  printf '%s\n' "DELETE FROM orders;"
  printf '%s\n' "DELETE FROM activity_sku_stock;"
  printf '%s\n' "DELETE FROM activity;"
  printf '%s\n' "INSERT INTO activity(id,name,status,starts_at,ends_at,created_at) VALUES ('experiment-activity','Experiment','ENABLED',DATE_SUB(NOW(6), INTERVAL 1 HOUR),DATE_ADD(NOW(6), INTERVAL 1 HOUR),NOW(6));"

  experiment_base_stock=$((experiment_stock / experiment_sku_count))
  experiment_remainder=$((experiment_stock % experiment_sku_count))
  for ((experiment_index=1; experiment_index<=experiment_sku_count; experiment_index++)); do
    experiment_sku_stock="$experiment_base_stock"
    if (( experiment_index <= experiment_remainder )); then
      experiment_sku_stock=$((experiment_sku_stock + 1))
    fi
    printf "INSERT INTO activity_sku_stock(id,activity_id,sku_code,unit_price,currency,initial_stock,available_stock,reserved_stock,sold_stock,version,created_at,updated_at) VALUES ('experiment-sku-%d','experiment-activity','EXPERIMENT-%d',99.00,'CNY',%d,%d,0,0,0,NOW(6),NOW(6));\n" \
      "$experiment_index" "$experiment_index" "$experiment_sku_stock" "$experiment_sku_stock"
  done
} > "$experiment_sql_path"

docker compose exec -T mysql mysql -uflashflow -pflashflow flashflow < "$experiment_sql_path"
