package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.ordering.OrderApplicationService;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.OrderResultCode;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.support.MySqlIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderIntegrationTest extends MySqlIntegrationTest {
    @Autowired
    private OrderApplicationService orders;
    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void createsAndReplaysOneCommittedOrder() {
        fixture().activeSku("a1", "s1", 2);
        PlaceOrderCommand command = new PlaceOrderCommand("u1", "s1", "key-1");
        double createdBefore = outcome("CREATED");

        OrderResult first = orders.place(command);
        OrderResult replay = orders.place(command);

        assertThat(first.code()).isEqualTo(OrderResultCode.CREATED);
        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available_stock FROM activity_sku_stock WHERE id='s1'", Integer.class))
                .isEqualTo(1);
        assertThat(outcome("CREATED") - createdBefore).isEqualTo(2);
    }

    @Test
    void rejectsInactiveSoldOutExistingAndConflictingIdempotency() {
        double inactiveBefore = outcome("ACTIVITY_NOT_ACTIVE");
        double soldOutBefore = outcome("SOLD_OUT");
        double existingBefore = outcome("EXISTING_EFFECTIVE_ORDER");
        double idempotencyConflictBefore = outcome("IDEMPOTENCY_CONFLICT");
        fixture().inactiveSku("a0", "s0", 1);
        assertThat(orders.place(new PlaceOrderCommand("u0", "s0", "inactive")).code())
                .isEqualTo(OrderResultCode.ACTIVITY_NOT_ACTIVE);

        fixture().activeSku("a1", "s1", 0);
        assertThat(orders.place(new PlaceOrderCommand("u1", "s1", "soldout")).code())
                .isEqualTo(OrderResultCode.SOLD_OUT);

        fixture().activeSku("a2", "s2", 2);
        OrderResult created = orders.place(new PlaceOrderCommand("u2", "s2", "first"));
        assertThat(created.code()).isEqualTo(OrderResultCode.CREATED);
        assertThat(orders.place(new PlaceOrderCommand("u2", "s2", "second")).code())
                .isEqualTo(OrderResultCode.EXISTING_EFFECTIVE_ORDER);
        assertThat(orders.place(new PlaceOrderCommand("u2", "s1", "first")).code())
                .isEqualTo(OrderResultCode.IDEMPOTENCY_CONFLICT);
        assertThat(outcome("ACTIVITY_NOT_ACTIVE") - inactiveBefore).isEqualTo(1);
        assertThat(outcome("SOLD_OUT") - soldOutBefore).isEqualTo(1);
        assertThat(outcome("EXISTING_EFFECTIVE_ORDER") - existingBefore).isEqualTo(1);
        assertThat(outcome("IDEMPOTENCY_CONFLICT") - idempotencyConflictBefore).isEqualTo(1);
    }

    private double outcome(String code) {
        var counter = meterRegistry.find("flashflow.order.outcome").tag("code", code).counter();
        return counter == null ? 0 : counter.count();
    }
}
