package dev.flashflow.inventory;

import dev.flashflow.inventory.persistence.InventoryMapper;
import dev.flashflow.shared.config.FlashFlowProperties;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public final class ConditionalAtomicReservationStrategy implements InventoryReservationStrategy {
    private final InventoryMapper inventoryMapper;

    public ConditionalAtomicReservationStrategy(InventoryMapper inventoryMapper) {
        this.inventoryMapper = inventoryMapper;
    }

    @Override
    public FlashFlowProperties.Strategy kind() {
        return FlashFlowProperties.Strategy.CONDITIONAL_ATOMIC;
    }

    @Override
    public ReservationAttempt reserve(String skuId, LocalDateTime now) {
        return inventoryMapper.reserveConditional(skuId, now) == 1
                ? ReservationAttempt.RESERVED
                : ReservationAttempt.SOLD_OUT;
    }
}

