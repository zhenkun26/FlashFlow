package dev.flashflow.messaging;

import java.time.LocalDateTime;

public record DelayedExpirationEnvelope(
        int schemaVersion, String orderId, LocalDateTime expectedExpiresAt, String traceId) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public DelayedExpirationEnvelope {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported delayed-expiration schema version");
        }
        if (orderId == null || orderId.isBlank() || orderId.length() > 36) {
            throw new IllegalArgumentException("orderId is required and must be at most 36 characters");
        }
        if (expectedExpiresAt == null) throw new IllegalArgumentException("expectedExpiresAt is required");
        if (traceId == null || traceId.isBlank() || traceId.length() > 128) {
            throw new IllegalArgumentException("traceId is required and must be at most 128 characters");
        }
    }
}
