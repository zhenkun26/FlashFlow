package dev.flashflow.inventory;

import dev.flashflow.shared.config.FlashFlowProperties;
import java.time.LocalDateTime;

public interface InventoryReservationStrategy {
    FlashFlowProperties.Strategy kind();

    ReservationAttempt reserve(String skuId, LocalDateTime now);
}

