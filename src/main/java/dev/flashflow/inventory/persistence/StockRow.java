package dev.flashflow.inventory.persistence;

public record StockRow(
        String id,
        int initialStock,
        int availableStock,
        int reservedStock,
        int soldStock,
        long version) {
}

