package dev.flashflow.messaging;

import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.PlaceOrderCommand;

@FunctionalInterface
public interface OrderCommandExecutor {
    OrderResult execute(PlaceOrderCommand command);
}
