package dev.flashflow.inventory;

import dev.flashflow.inventory.persistence.InventoryMapper;
import dev.flashflow.inventory.persistence.StockRow;
import dev.flashflow.shared.config.FlashFlowProperties;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("lab")
public final class UnsafeReadThenWriteStrategy implements InventoryReservationStrategy {
    private final InventoryMapper inventoryMapper;
    private final UnsafeInterleavingHook interleavingHook;

    public UnsafeReadThenWriteStrategy(InventoryMapper inventoryMapper, UnsafeInterleavingHook interleavingHook) {
        this.inventoryMapper = inventoryMapper;
        this.interleavingHook = interleavingHook;
    }

    @Override
    public FlashFlowProperties.Strategy kind() {
        return FlashFlowProperties.Strategy.UNSAFE_READ_THEN_WRITE;
    }

    @Override
    public ReservationAttempt reserve(String skuId, LocalDateTime now) {
        StockRow observed = inventoryMapper.findStock(skuId);
        if (observed == null || observed.availableStock() < 1) {
            return ReservationAttempt.SOLD_OUT;
        }
        interleavingHook.afterRead(observed);
        inventoryMapper.overwriteUnsafe(
                skuId,
                observed.availableStock() - 1,
                observed.reservedStock() + 1,
                now);
        return ReservationAttempt.RESERVED;
    }
}
