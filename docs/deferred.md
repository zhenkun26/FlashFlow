# Deferred after V4

The following are intentionally absent and require separate OpenSpec changes:

- Debezium/CDC comparison with the polling Transactional Outbox; the application-owned polling path is the V4 implementation under test.
- Automatic replay of `INVALID` or `EXHAUSTED` Outbox records and automatic replay of dead-lettered commands; replay must preserve the stable identity and re-check MySQL business truth.
- Automated refund execution and provider reconciliation.
- Multiple order lines, quantity greater than one, seat selection, cart, catalog, coupon, fulfillment, and logistics.
- Microservice extraction, sharding, Kubernetes, multi-region or high-availability claims.

The presence of k6, Micrometer, or concurrency tests does not authorize a throughput claim until a reproducible report exists.
