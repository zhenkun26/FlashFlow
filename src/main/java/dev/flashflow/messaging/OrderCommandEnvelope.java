package dev.flashflow.messaging;

import java.time.Instant;

public record OrderCommandEnvelope(
        int schemaVersion,
        String commandId,
        String callerId,
        String activitySkuId,
        String idempotencyKey,
        String payloadFingerprint,
        Instant createdAt,
        String traceId) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public OrderCommandEnvelope {
        require(schemaVersion == CURRENT_SCHEMA_VERSION, "Unsupported command schema version");
        commandId = text(commandId, "commandId", 64);
        callerId = text(callerId, "callerId", 64);
        activitySkuId = text(activitySkuId, "activitySkuId", 64);
        idempotencyKey = text(idempotencyKey, "idempotencyKey", 128);
        payloadFingerprint = text(payloadFingerprint, "payloadFingerprint", 64);
        require(createdAt != null, "createdAt is required");
        traceId = text(traceId, "traceId", 128);
        require(commandId.matches("[0-9a-f]{64}"), "commandId must be a SHA-256 hex digest");
        require(payloadFingerprint.matches("[0-9a-f]{64}"), "payloadFingerprint must be a SHA-256 hex digest");
    }

    private static String text(String value, String field, int max) {
        require(value != null && !value.isBlank() && value.length() <= max,
                field + " is required and must be at most " + max + " characters");
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
