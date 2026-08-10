package dev.flashflow.admission.persistence;

import java.time.LocalDateTime;

public record CommandAdmissionRow(
        String commandId,
        String status,
        String transportCause,
        LocalDateTime updatedAt,
        LocalDateTime deadLetteredAt) {
}
