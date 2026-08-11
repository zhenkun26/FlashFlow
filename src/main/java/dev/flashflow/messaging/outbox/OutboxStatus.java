package dev.flashflow.messaging.outbox;

public enum OutboxStatus {
    READY,
    CLAIMED,
    RETRYABLE,
    ACKNOWLEDGED,
    INVALID,
    EXHAUSTED
}
