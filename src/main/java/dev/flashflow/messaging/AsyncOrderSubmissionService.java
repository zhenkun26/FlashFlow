package dev.flashflow.messaging;

import dev.flashflow.ordering.PlaceOrderCommand;

public interface AsyncOrderSubmissionService {
    AsyncOrderSubmission submit(PlaceOrderCommand command, String traceId);
}
