package dev.flashflow.messaging;

import dev.flashflow.ordering.OrderApplicationService;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.PlaceOrderCommand;
import org.springframework.stereotype.Component;

@Component
public final class DefaultOrderCommandExecutor implements OrderCommandExecutor {
    private final OrderApplicationService ordering;

    public DefaultOrderCommandExecutor(OrderApplicationService ordering) {
        this.ordering = ordering;
    }

    @Override
    public OrderResult execute(PlaceOrderCommand command) {
        return ordering.place(command);
    }
}
