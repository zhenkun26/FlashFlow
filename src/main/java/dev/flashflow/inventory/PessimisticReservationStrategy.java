package dev.flashflow.inventory;

import dev.flashflow.inventory.persistence.InventoryMapper;
import dev.flashflow.inventory.persistence.StockRow;
import dev.flashflow.shared.config.FlashFlowProperties;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public final class PessimisticReservationStrategy implements InventoryReservationStrategy {
    private final InventoryMapper inventoryMapper;

    public PessimisticReservationStrategy(InventoryMapper inventoryMapper) {
        this.inventoryMapper = inventoryMapper;
    }

    @Override
    public FlashFlowProperties.Strategy kind() {
        return FlashFlowProperties.Strategy.PESSIMISTIC;
    }

    @Override
    public ReservationAttempt reserve(String skuId, LocalDateTime now) {
        StockRow locked = inventoryMapper.findStockForUpdate(skuId);
        if (locked == null || locked.availableStock() < 1) {
            return ReservationAttempt.SOLD_OUT;
        }
        int changed = inventoryMapper.reserveConditional(skuId, now);
        if (changed != 1) {
            throw new IllegalStateException("Locked stock row changed unexpectedly");
        }
        return ReservationAttempt.RESERVED;
    }
}

