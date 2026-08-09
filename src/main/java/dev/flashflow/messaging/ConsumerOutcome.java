package dev.flashflow.messaging;

public enum ConsumerOutcome {
    ACKNOWLEDGED,
    RETRY,
    REJECTED,
    UNRESOLVED
}
