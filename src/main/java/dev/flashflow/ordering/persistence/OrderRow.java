package dev.flashflow.ordering.persistence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderRow(
        String id,
        String userId,
        String activitySkuId,
        String status,
        BigDecimal unitPrice,
        String currency,
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long version) {
}

