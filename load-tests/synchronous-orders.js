import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const createdResponses = new Counter('flashflow_created_responses');
const inactiveResponses = new Counter('flashflow_activity_not_active_responses');
const soldOutResponses = new Counter('flashflow_sold_out_responses');
const existingOrderResponses = new Counter('flashflow_existing_order_responses');
const idempotencyConflictResponses = new Counter('flashflow_idempotency_conflict_responses');
const retryableContentionResponses = new Counter('flashflow_retryable_contention_responses');
const unexpectedResponses = new Counter('flashflow_unexpected_responses');

export const options = {
  summaryTrendStats: ['avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    checks: ['rate==1'],
  },
  scenarios: {
    synchronous_orders: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 20),
      duration: __ENV.DURATION || '30s',
    },
  },
};

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const skuPrefix = __ENV.SKU_PREFIX || 'experiment-sku';
const skuCount = Number(__ENV.SKU_COUNT || 1);
const skuDistribution = __ENV.SKU_DISTRIBUTION || 'SINGLE_HOT';
const caseId = __ENV.CASE_ID || 'adhoc';

function selectedSku() {
  if (skuDistribution === 'SINGLE_HOT' || skuCount === 1) return `${skuPrefix}-1`;
  if (skuDistribution === 'ZIPF_HOT' && (__ITER % 10) < 8) return `${skuPrefix}-1`;
  return `${skuPrefix}-${((__VU + __ITER) % skuCount) + 1}`;
}

export default function () {
  const unique = `${__VU}-${__ITER}`;
  const skuId = selectedSku();
  const response = http.post(
    `${baseUrl}/api/v1/orders`,
    JSON.stringify({ userId: `k6-${caseId}-${unique}`, activitySkuId: skuId }),
    {
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `k6-${unique}` },
      responseCallback: http.expectedStatuses(201, 409, 503),
    },
  );
  check(response, {
    'business response returned': (r) => [201, 409, 503].includes(r.status),
  });
  let code = 'UNEXPECTED';
  try {
    code = JSON.parse(response.body).code || code;
  } catch (_) {
    // Transport failures and non-JSON responses remain unexpected evidence.
  }
  if (response.status === 201 && code === 'CREATED') createdResponses.add(1);
  else if (response.status === 409 && code === 'ACTIVITY_NOT_ACTIVE') inactiveResponses.add(1);
  else if (response.status === 409 && code === 'SOLD_OUT') soldOutResponses.add(1);
  else if (response.status === 409 && code === 'EXISTING_EFFECTIVE_ORDER') existingOrderResponses.add(1);
  else if (response.status === 400 && code === 'IDEMPOTENCY_CONFLICT') idempotencyConflictResponses.add(1);
  else if (response.status === 503 && code === 'RETRYABLE_CONTENTION') retryableContentionResponses.add(1);
  else unexpectedResponses.add(1);
}

function count(data, metric) {
  return data.metrics[metric] ? Number(data.metrics[metric].values.count || 0) : 0;
}

function trend(data, name) {
  return Number((data.metrics.http_req_duration && data.metrics.http_req_duration.values[name]) || 0);
}

export function handleSummary(data) {
  const summary = {
    totalRequests: count(data, 'http_reqs'),
    outcomes: {
      CREATED: count(data, 'flashflow_created_responses'),
      ACTIVITY_NOT_ACTIVE: count(data, 'flashflow_activity_not_active_responses'),
      SOLD_OUT: count(data, 'flashflow_sold_out_responses'),
      EXISTING_EFFECTIVE_ORDER: count(data, 'flashflow_existing_order_responses'),
      IDEMPOTENCY_CONFLICT: count(data, 'flashflow_idempotency_conflict_responses'),
      RETRYABLE_CONTENTION: count(data, 'flashflow_retryable_contention_responses'),
      UNEXPECTED: count(data, 'flashflow_unexpected_responses'),
    },
    latencyMillis: {
      mean: trend(data, 'avg'),
      p90: trend(data, 'p(90)'),
      p95: trend(data, 'p(95)'),
      p99: trend(data, 'p(99)'),
      max: trend(data, 'max'),
    },
  };
  const output = __ENV.K6_SUMMARY_PATH || 'k6-summary.json';
  return { [output]: JSON.stringify(summary, null, 2) };
}
