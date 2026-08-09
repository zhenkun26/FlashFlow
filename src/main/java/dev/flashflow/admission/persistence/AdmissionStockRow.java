package dev.flashflow.admission.persistence;

public record AdmissionStockRow(String skuId, int availableStock, int reservedStock, int soldStock) {
}
