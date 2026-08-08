package dev.flashflow.inventory;

import dev.flashflow.inventory.persistence.StockRow;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("lab")
public final class NoOpUnsafeInterleavingHook implements UnsafeInterleavingHook {
    @Override
    public void afterRead(StockRow observed) {
        // Lab tests can replace this bean with a barrier to force a specific lost-update interleaving.
    }
}

