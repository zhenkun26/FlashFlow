package dev.flashflow.inventory.persistence;

import java.time.LocalDateTime;

public record ReservationRow(
        String id,
        String orderId,
        String activitySkuId,
        int quantity,
        String status,
        LocalDateTime expiresAt) {
}

