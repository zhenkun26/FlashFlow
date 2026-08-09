package dev.flashflow.admission;

import java.time.Instant;

public record AdmissionCommand(String skuId, String admissionId, String userDigest, Instant resolutionDeadline) {
}
