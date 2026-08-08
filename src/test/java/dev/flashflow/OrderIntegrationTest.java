package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.ordering.OrderApplicationService;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.OrderResultCode;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderIntegrationTest extends MySqlIntegrationTest {
    @Autowired
    private OrderApplicationService orders;

    @Test
    void createsAndReplaysOneCommittedOrder() {
        fixture().activeSku("a1", "s1", 2);
        PlaceOrderCommand command = new PlaceOrderCommand("u1", "s1", "key-1");

        OrderResult first = orders.place(command);
        OrderResult replay = orders.place(command);

        assertThat(first.code()).isEqualTo(OrderResultCode.CREATED);
        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available_stock FROM activity_sku_stock WHERE id='s1'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsInactiveSoldOutExistingAndConflictingIdempotency() {
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
    }
}

