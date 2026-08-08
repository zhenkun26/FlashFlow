package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

import dev.flashflow.expiration.ExpirationService;
import dev.flashflow.expiration.ExpirationTransactionHook;
import dev.flashflow.ordering.OrderApplicationService;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

class ExpirationRollbackIntegrationTest extends MySqlIntegrationTest {
    @Autowired private OrderApplicationService orders;
    @Autowired private ExpirationService expiration;
    @MockBean private ExpirationTransactionHook transactionHook;

    @Test
    void interruptionBeforeCommitRollsBackAllExpirationEffects() {
        fixture().activeSku("a1", "s1", 1);
        OrderResult order = orders.place(new PlaceOrderCommand("u1", "s1", "k1"));
        jdbc.update("UPDATE orders SET expires_at = DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE id = ?", order.orderId());
        doThrow(new SimulatedWorkerCrash()).when(transactionHook).beforeCommit(anyList());

        assertThatThrownBy(expiration::expireBatch).isInstanceOf(SimulatedWorkerCrash.class);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.orderId()))
                .isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT reserved_stock FROM activity_sku_stock WHERE id='s1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_claim WHERE order_id = ?", Integer.class,
                order.orderId())).isEqualTo(1);
    }

    private static final class SimulatedWorkerCrash extends RuntimeException {
    }
}

