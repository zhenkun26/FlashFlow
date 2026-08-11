# V4 Transactional Outbox publication

```text
POST /api/v2/orders                         polling dispatcher
  -> stable command identity                       |
  -> Redis admission                               v
  -> one MySQL transaction                 READY / RETRYABLE / expired CLAIMED
       command ledger = ACCEPTED                    |
       outbox = READY                               v
  -> 202 Accepted                          short SKIP LOCKED claim transaction
                                                     |
                                                     v
                                             RocketMQ publish
                                               SEND_OK -> ACKNOWLEDGED
                                               failure -> RETRYABLE / EXHAUSTED
                                                     |
                                                     v
                                         existing idempotent consumer
                                                     |
                                                     v
                                          MySQL business transaction
```

`OUTBOX` acceptance means MySQL durably committed one command and one immutable publish-ready envelope. It does not mean RocketMQ has acknowledged the message and does not mean an order exists. `DIRECT` retains the V3 inline `SEND_OK` acceptance boundary, while `DISABLED` creates no Broker or dispatcher clients.

The Outbox and command ledger remain separate. The ledger converges caller status and the consumer result; the Outbox owns immutable transport payload, retry scheduling, lease ownership, and Broker acknowledgement. MySQL order, inventory, payment, and expiration tables remain the only business truth.

The dispatcher claims bounded batches in a short MySQL transaction using `FOR UPDATE SKIP LOCKED`, commits a unique lease token, and performs Broker I/O after the transaction. Only the current token can record a result. Expired claims are recoverable and may republish a message that the Broker already accepted; the stable command identity and existing idempotent consumer therefore remain mandatory.

Automatic dispatch stops at `INVALID` or `EXHAUSTED`. Those transport dispositions do not prove absence of an earlier delivery or business effect and do not authorize Redis capacity release. Reconciliation joins command, Outbox, Redis, and committed MySQL facts and stays blocked when evidence is missing or contradictory.

Polling Outbox is the only V4 publication implementation. CDC/Debezium, automatic replay, exactly-once transport, production availability, persistence, capacity, and latency SLA remain outside the evidence boundary.
