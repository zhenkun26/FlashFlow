# Deferred after V1

The following are intentionally absent and require separate OpenSpec changes:

- Redis Lua admission tokens, token compensation, and Redis/MySQL reconciliation.
- RocketMQ asynchronous order commands, delayed close, consumer Inbox, retry, DLQ, and Transactional Outbox.
- Automated refund execution and provider reconciliation.
- Debezium CDC comparison.
- Multiple order lines, quantity greater than one, seat selection, cart, catalog, coupon, fulfillment, and logistics.
- Microservice extraction, sharding, Kubernetes, multi-region or high-availability claims.

The presence of k6, Micrometer, or concurrency tests does not authorize a throughput claim until a reproducible report exists.

