package dev.flashflow.messaging;

public enum CommandStatus {
    PREPARED,
    ACCEPTED,
    PROCESSING,
    COMPLETED,
    REJECTED,
    RETRYABLE,
    UNRESOLVED
}
