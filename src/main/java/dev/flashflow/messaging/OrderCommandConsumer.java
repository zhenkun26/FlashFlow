package dev.flashflow.messaging;

public interface OrderCommandConsumer {
    CommandConsumptionResult consume(OrderCommandEnvelope envelope);
}
