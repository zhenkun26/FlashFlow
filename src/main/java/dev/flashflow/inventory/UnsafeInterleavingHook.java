package dev.flashflow.inventory;

import dev.flashflow.inventory.persistence.StockRow;

@FunctionalInterface
public interface UnsafeInterleavingHook {
    void afterRead(StockRow observed);
}

