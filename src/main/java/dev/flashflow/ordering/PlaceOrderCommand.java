package dev.flashflow.ordering;

import java.util.Objects;

public record PlaceOrderCommand(String userId, String activitySkuId, String idempotencyKey) {
    public PlaceOrderCommand {
        userId = requireText(userId, "userId", 64);
        activitySkuId = requireText(activitySkuId, "activitySkuId", 64);
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey", 128);
    }

    private static String requireText(String value, String name, int max) {
        Objects.requireNonNull(value, name + " is required");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalArgumentException(name + " must contain 1-" + max + " characters");
        }
        return normalized;
    }
}

