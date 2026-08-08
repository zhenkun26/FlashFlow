package dev.flashflow.ordering;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderSummary(
        String orderId,
        String userId,
        String activitySkuId,
        OrderStatus status,
        BigDecimal amount,
        String currency,
        LocalDateTime expiresAt,
        String reservationStatus,
        String paymentStatus,
        String paymentApplyStatus) {
}

