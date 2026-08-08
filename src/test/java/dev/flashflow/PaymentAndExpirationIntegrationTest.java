package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.expiration.ExpirationService;
import dev.flashflow.ordering.OrderApplicationService;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.payment.PaymentApplicationService;
import dev.flashflow.payment.PaymentCallbackCommand;
import dev.flashflow.payment.PaymentResult;
import dev.flashflow.support.MySqlIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentAndExpirationIntegrationTest extends MySqlIntegrationTest {
    @Autowired private OrderApplicationService orders;
    @Autowired private PaymentApplicationService payments;
    @Autowired private ExpirationService expiration;

    @Test
    void appliesPaymentExactlyOnceAndExpirationLoses() {
        fixture().activeSku("a1", "s1", 1);
        OrderResult order = orders.place(new PlaceOrderCommand("u1", "s1", "k1"));
        PaymentCallbackCommand callback = callback("e1", "tx1", order.orderId());

        assertThat(payments.apply(callback).code()).isEqualTo(PaymentResult.Code.APPLIED);
        assertThat(payments.apply(callback).code()).isEqualTo(PaymentResult.Code.DUPLICATE);
        jdbc.update("UPDATE orders SET expires_at = DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE id = ?", order.orderId());
        assertThat(expiration.expireBatch()).isZero();
        assertThat(jdbc.queryForObject("SELECT sold_stock FROM activity_sku_stock WHERE id='s1'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void expirationWinsAndRepeatedLatePaymentCreatesOneCompensationCase() {
        fixture().activeSku("a1", "s1", 1);
        OrderResult order = orders.place(new PlaceOrderCommand("u1", "s1", "k1"));
        jdbc.update("UPDATE orders SET expires_at = DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE id = ?", order.orderId());

        assertThat(expiration.expireBatch()).isEqualTo(1);
        assertThat(expiration.expireBatch()).isZero();
        PaymentCallbackCommand callback = callback("e1", "tx1", order.orderId());
        assertThat(payments.apply(callback).code()).isEqualTo(PaymentResult.Code.REFUND_REQUIRED);
        assertThat(payments.apply(callback).code()).isEqualTo(PaymentResult.Code.DUPLICATE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM compensation_case", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available_stock FROM activity_sku_stock WHERE id='s1'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsProviderTransactionReuseAcrossOrders() {
        fixture().activeSku("a1", "s1", 2);
        OrderResult one = orders.place(new PlaceOrderCommand("u1", "s1", "k1"));
        OrderResult two = orders.place(new PlaceOrderCommand("u2", "s1", "k2"));
        assertThat(payments.apply(callback("e1", "shared-tx", one.orderId())).code())
                .isEqualTo(PaymentResult.Code.APPLIED);
        assertThat(payments.apply(callback("e2", "shared-tx", two.orderId())).code())
                .isEqualTo(PaymentResult.Code.PROVIDER_TRANSACTION_CONFLICT);
    }

    private static PaymentCallbackCommand callback(String eventId, String transactionId, String orderId) {
        return new PaymentCallbackCommand(eventId, transactionId, orderId,
                new BigDecimal("99.00"), "CNY", LocalDateTime.now());
    }
}

