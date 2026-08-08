package dev.flashflow.ordering;

import java.time.LocalDateTime;

public record OrderResult(
        OrderResultCode code,
        String orderId,
        OrderStatus status,
        LocalDateTime expiresAt,
        String message) {

    public static OrderResult rejected(OrderResultCode code, String message) {
        return new OrderResult(code, null, null, null, message);
    }
}

