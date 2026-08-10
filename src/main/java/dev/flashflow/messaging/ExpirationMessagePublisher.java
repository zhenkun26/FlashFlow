package dev.flashflow.messaging;

public interface ExpirationMessagePublisher {
    PublicationResult publish(DelayedExpirationEnvelope envelope);
}
