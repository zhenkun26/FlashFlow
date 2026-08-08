import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const createdResponses = new Counter('flashflow_created_responses');
const businessRejections = new Counter('flashflow_business_rejections');
const overloadResponses = new Counter('flashflow_overload_responses');

export const options = {
  scenarios: {
    synchronous_orders: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 20),
      duration: __ENV.DURATION || '30s',
    },
  },
};

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const skuId = __ENV.SKU_ID || 'demo-sku';

export default function () {
  const unique = `${__VU}-${__ITER}`;
  const response = http.post(
    `${baseUrl}/api/v1/orders`,
    JSON.stringify({ userId: `k6-user-${unique}`, activitySkuId: skuId }),
    {
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `k6-${unique}` },
      responseCallback: http.expectedStatuses(201, 409, 503),
    },
  );
  check(response, {
    'business response returned': (r) => [201, 409, 503].includes(r.status),
  });
  if (response.status === 201) createdResponses.add(1);
  if (response.status === 409) businessRejections.add(1);
  if (response.status === 503) overloadResponses.add(1);
}
