package dev.flashflow.messaging.outbox;

import dev.flashflow.messaging.OrderCommandEnvelope;

public interface OutboxAcceptanceTransactionHook {
    default void afterCommandPrepared(OrderCommandEnvelope envelope) {
    }

    default void afterOutboxPrepared(OrderCommandEnvelope envelope) {
    }
}
