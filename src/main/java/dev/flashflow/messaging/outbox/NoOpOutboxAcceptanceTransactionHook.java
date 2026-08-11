package dev.flashflow.messaging.outbox;

import org.springframework.stereotype.Component;

@Component
public final class NoOpOutboxAcceptanceTransactionHook implements OutboxAcceptanceTransactionHook {
}
