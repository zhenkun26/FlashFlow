package dev.flashflow.inventory;

import dev.flashflow.inventory.persistence.InventoryMapper;
import dev.flashflow.inventory.persistence.StockRow;
import dev.flashflow.shared.config.FlashFlowProperties;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public final class OptimisticReservationStrategy implements InventoryReservationStrategy {
    private final InventoryMapper inventoryMapper;

    public OptimisticReservationStrategy(InventoryMapper inventoryMapper) {
        this.inventoryMapper = inventoryMapper;
    }

    @Override
    public FlashFlowProperties.Strategy kind() {
        return FlashFlowProperties.Strategy.OPTIMISTIC;
    }

    @Override
    public ReservationAttempt reserve(String skuId, LocalDateTime now) {
        StockRow observed = inventoryMapper.findStock(skuId);
        if (observed == null || observed.availableStock() < 1) {
            return ReservationAttempt.SOLD_OUT;
        }
        return inventoryMapper.reserveOptimistic(skuId, observed.version(), now) == 1
                ? ReservationAttempt.RESERVED
                : ReservationAttempt.CONFLICT;
    }
}

