package dev.flashflow.messaging;

public enum PublicationOutcome {
    DURABLY_QUEUED,
    DEFINITELY_NOT_PUBLISHED,
    BROKER_ACKNOWLEDGED,
    AMBIGUOUS
}
