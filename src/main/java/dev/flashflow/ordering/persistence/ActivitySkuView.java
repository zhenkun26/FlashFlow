package dev.flashflow.ordering.persistence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ActivitySkuView(
        String skuId,
        String activityStatus,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        BigDecimal unitPrice,
        String currency) {

    public boolean acceptsOrdersAt(LocalDateTime now) {
        return "ENABLED".equals(activityStatus)
                && !now.isBefore(startsAt)
                && now.isBefore(endsAt);
    }
}

