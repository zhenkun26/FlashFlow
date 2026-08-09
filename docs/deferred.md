# Deferred after V2.1

The following are intentionally absent and require separate OpenSpec changes:

- V3 live RocketMQ producer/consumer wiring, public asynchronous order routes, broker-backed delayed close, retry/DLQ policy, and production operations evidence.
- V4 Transactional Outbox/CDC publication, dispatcher leases, and broker-confirmation recovery. The V2.1 command ledger is deliberately not an Outbox.
- Automated refund execution and provider reconciliation.
- Debezium CDC comparison.
- Multiple order lines, quantity greater than one, seat selection, cart, catalog, coupon, fulfillment, and logistics.
- Microservice extraction, sharding, Kubernetes, multi-region or high-availability claims.

The presence of k6, Micrometer, or concurrency tests does not authorize a throughput claim until a reproducible report exists.
