package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.expiration.ExpirationService;
import dev.flashflow.messaging.DelayedExpirationEnvelope;
import dev.flashflow.messaging.ExpirationTriggerOutcome;
import dev.flashflow.ordering.OrderApplicationService;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.payment.PaymentApplicationService;
import dev.flashflow.payment.PaymentCallbackCommand;
import dev.flashflow.support.MySqlIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DelayedExpirationIntegrationTest extends MySqlIntegrationTest {
    @Autowired private OrderApplicationService orders;
    @Autowired private ExpirationService expiration;
    @Autowired private PaymentApplicationService payments;

    @Test
    void earlyAndDuplicateTriggersAreSafeAndScannerRecoversMissingTrigger() {
        fixture().activeSku("a1", "s1", 2);
        OrderResult early = orders.place(new PlaceOrderCommand("u1", "s1", "k1"));
        assertThat(expiration.expireOne(envelope(early))).isEqualTo(ExpirationTriggerOutcome.TOO_EARLY);

        expireInDatabase(early.orderId());
        OrderResult refreshed = orders.place(new PlaceOrderCommand("u1", "s1", "k1"));
        assertThat(expiration.expireOne(envelope(refreshed))).isEqualTo(ExpirationTriggerOutcome.CLOSED);
        assertThat(expiration.expireOne(envelope(refreshed))).isEqualTo(ExpirationTriggerOutcome.SKIPPED_STATE);

        OrderResult missing = orders.place(new PlaceOrderCommand("u2", "s1", "k2"));
        expireInDatabase(missing.orderId());
        assertThat(expiration.expireBatch()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available_stock FROM activity_sku_stock WHERE id='s1'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void paidOrderAndTriggerScannerRacePreserveOneLegalTransition() throws Exception {
        fixture().activeSku("a1", "s1", 2);
        OrderResult paid = orders.place(new PlaceOrderCommand("u1", "s1", "k1"));
        payments.apply(new PaymentCallbackCommand("e1", "tx1", paid.orderId(),
                new BigDecimal("99.00"), "CNY", LocalDateTime.now()));
        expireInDatabase(paid.orderId());
        assertThat(expiration.expireOne(envelope(paid))).isEqualTo(ExpirationTriggerOutcome.SKIPPED_STATE);

        OrderResult racing = orders.place(new PlaceOrderCommand("u2", "s1", "k2"));
        expireInDatabase(racing.orderId());
        OrderResult refreshed = orders.place(new PlaceOrderCommand("u2", "s1", "k2"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var trigger = executor.submit(() -> expiration.expireOne(envelope(refreshed)));
            var scanner = executor.submit(expiration::expireBatch);
            trigger.get(10, TimeUnit.SECONDS);
            scanner.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE operation_id=?",
                Integer.class, "release:" + racing.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_claim WHERE order_id=?",
                Integer.class, racing.orderId())).isZero();
    }

    private void expireInDatabase(String orderId) {
        jdbc.update("UPDATE orders SET expires_at = DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE id = ?", orderId);
    }

    private DelayedExpirationEnvelope envelope(OrderResult result) {
        LocalDateTime expiresAt = jdbc.queryForObject("SELECT expires_at FROM orders WHERE id=?",
                LocalDateTime.class, result.orderId());
        return new DelayedExpirationEnvelope(1, result.orderId(), expiresAt, "trace");
    }
}
