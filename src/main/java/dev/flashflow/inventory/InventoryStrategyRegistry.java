package dev.flashflow.inventory;

import dev.flashflow.shared.config.FlashFlowProperties;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class InventoryStrategyRegistry {
    private final Map<FlashFlowProperties.Strategy, InventoryReservationStrategy> strategies;

    public InventoryStrategyRegistry(List<InventoryReservationStrategy> available) {
        Map<FlashFlowProperties.Strategy, InventoryReservationStrategy> indexed =
                new EnumMap<>(FlashFlowProperties.Strategy.class);
        for (InventoryReservationStrategy strategy : available) {
            if (indexed.put(strategy.kind(), strategy) != null) {
                throw new IllegalStateException("Duplicate inventory strategy: " + strategy.kind());
            }
        }
        this.strategies = Map.copyOf(indexed);
    }

    public InventoryReservationStrategy require(FlashFlowProperties.Strategy kind) {
        InventoryReservationStrategy strategy = strategies.get(kind);
        if (strategy == null) {
            throw new IllegalStateException("Inventory strategy is unavailable in this profile: " + kind);
        }
        return strategy;
    }
}

