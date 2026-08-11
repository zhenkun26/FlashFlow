# V3 live RocketMQ ordering

```text
POST /api/v2/orders
  -> durable command identity
  -> Redis admission
  -> direct RocketMQ publish
       SEND_OK -> 202 ACCEPTED
       definite failure -> RETRYABLE + safe release
       ambiguous -> UNRESOLVED + quarantine

RocketMQ order delivery
  -> version/fingerprint validation
  -> conditional command claim
  -> existing MySQL ordering transaction
  -> durable command result
  -> acknowledge delivery
  -> publish advisory delayed expiration after commit

Delayed delivery or scanner
  -> same locked MySQL expiration transaction
```

MySQL is the only business source of truth. Redis controls hot-path capacity and RocketMQ transports work; neither can create an order or inventory result. Delivery is at-least-once, so stable command identity and existing idempotency are the correctness mechanism.

V3 direct publication deliberately exposes the preparation-to-Broker crash window. The ledger records identity and observed state but stores no dispatch payload and runs no polling publisher. The V4 `OUTBOX` mode adds polling recovery without changing this `DIRECT` control or the consumer identity contract.

Metrics use bounded operation/outcome tags. Raw user IDs and idempotency keys are excluded from Broker keys, metric tags, dead-letter metadata, and retained reports.

The verification graph includes explicit one-shot or always-fail injection points around consumer delegation and acknowledgement. They are disabled by default and exist to prove redelivery, retry exhaustion, custom DLQ routing, and delayed-trigger convergence against the real local Broker; they are not runtime recovery mechanisms.
