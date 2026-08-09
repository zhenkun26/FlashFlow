package dev.flashflow.messaging;

public enum DeliveryOutcome {
    DELIVERED,
    REDELIVERED,
    UNSUPPORTED_VERSION,
    CONFLICTING_PAYLOAD,
    POISON_ENVELOPE
}
