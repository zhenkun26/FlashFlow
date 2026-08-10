package dev.flashflow.messaging;

public interface OrderCommandPublisher {
    PublicationResult publish(OrderCommandEnvelope envelope);
}
