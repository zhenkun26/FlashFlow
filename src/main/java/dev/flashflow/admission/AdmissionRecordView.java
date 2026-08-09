package dev.flashflow.admission;

import java.time.Instant;

public record AdmissionRecordView(
        String admissionId, String userDigest, AdmissionState state, Instant resolutionDeadline) {
}
