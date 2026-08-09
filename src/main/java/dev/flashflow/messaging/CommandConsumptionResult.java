package dev.flashflow.messaging;

public record CommandConsumptionResult(
        String commandId,
        CommandStatus status,
        String resultCode,
        String orderId,
        ConsumerOutcome outcome,
        boolean acknowledgementEligible) {
}
