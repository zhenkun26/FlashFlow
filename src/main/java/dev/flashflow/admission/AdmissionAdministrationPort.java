package dev.flashflow.admission;

import java.util.List;

public interface AdmissionAdministrationPort {
    boolean beginGeneration(String skuId, String generation, int capacity, String fenceToken);
    boolean publishGeneration(String skuId, String generation, String fenceToken);
    AdmissionGenerationSnapshot snapshot(String skuId);
    List<AdmissionRecordView> records(String skuId);
    boolean seed(String skuId, String generation, String fenceToken, AdmissionRecordView record, boolean consumeCapacity);
}
