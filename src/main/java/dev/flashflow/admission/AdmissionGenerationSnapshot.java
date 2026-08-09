package dev.flashflow.admission;

public record AdmissionGenerationSnapshot(
        String generation,
        AdmissionGenerationState state,
        int initialCapacity,
        int remainingCapacity,
        long held,
        long confirmed,
        long released,
        long quarantined) {
}
