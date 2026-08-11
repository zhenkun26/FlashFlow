package dev.flashflow.messaging.outbox;

public record OutboxBacklog(long ready, long oldestAgeMillis) {
}
