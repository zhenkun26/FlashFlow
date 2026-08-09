package dev.flashflow.messaging;

public record PublicationResult(PublicationOutcome outcome, String cause) {
    public PublicationResult {
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        if (cause == null || cause.isBlank() || cause.length() > 64) {
            throw new IllegalArgumentException("cause must be bounded to 64 characters");
        }
    }
}
