package dev.flashflow.messaging;

import java.net.URI;

public record AsyncOrderSubmission(
        String commandId,
        CommandStatus status,
        URI statusLocation,
        PublicationOutcome publicationOutcome,
        PublicationResolution admissionResolution,
        String cause) {
    public boolean accepted() {
        return status == CommandStatus.ACCEPTED;
    }
}
