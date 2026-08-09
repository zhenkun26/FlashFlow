package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.admission.AdmissionGenerationSnapshot;
import dev.flashflow.admission.AdmissionKeys;
import dev.flashflow.admission.RedisLuaAdmissionAdapter;
import dev.flashflow.expiration.ExpirationService;
import dev.flashflow.ordering.OrderApplicationService;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.OrderResultCode;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.payment.PaymentApplicationService;
import dev.flashflow.payment.PaymentCallbackCommand;
import dev.flashflow.support.RedisIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RedisOrderingIntegrationTest extends RedisIntegrationTest {
    @Autowired private OrderApplicationService orders;
    @Autowired private ExpirationService expiration;
    @Autowired private PaymentApplicationService payments;
    @Autowired private RedisLuaAdmissionAdapter admission;

    @Test
    void admissionShieldsMySqlAndDurableReplaySurvivesRedisLoss() {
        fixture().activeSku("a1", "s1", 1);
        ready("s1", 1);

        OrderResult created = orders.place(new PlaceOrderCommand("u1", "s1", "k1"));
        assertThat(created.code()).isEqualTo(OrderResultCode.CREATED);
        assertThat(admission.snapshot("s1").confirmed()).isEqualTo(1);

        OrderResult deferred = orders.place(new PlaceOrderCommand("u2", "s1", "k2"));
        assertThat(deferred.code()).isEqualTo(OrderResultCode.RETRYABLE_CONTENTION);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class)).isEqualTo(1);

        redis.delete(new AdmissionKeys("s1").current());
        OrderResult replay = orders.place(new PlaceOrderCommand("u1", "s1", "k1"));
        assertThat(replay.orderId()).isEqualTo(created.orderId());
        assertThat(replay.code()).isEqualTo(OrderResultCode.CREATED);

        OrderResult newAttempt = orders.place(new PlaceOrderCommand("u3", "s1", "k3"));
        assertThat(newAttempt.code()).isEqualTo(OrderResultCode.RETRYABLE_CONTENTION);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameUserConvergesAndInactiveResultReleasesHeldToken() {
        fixture().activeSku("a1", "s1", 2);
        ready("s1", 2);
        OrderResult created = orders.place(new PlaceOrderCommand("u1", "s1", "k1"));
        OrderResult existing = orders.place(new PlaceOrderCommand("u1", "s1", "k2"));
        assertThat(created.code()).isEqualTo(OrderResultCode.CREATED);
        assertThat(existing.code()).isEqualTo(OrderResultCode.EXISTING_EFFECTIVE_ORDER);
        assertThat(admission.snapshot("s1").remainingCapacity()).isEqualTo(1);

        fixture().inactiveSku("a2", "s2", 1);
        ready("s2", 1);
        assertThat(orders.place(new PlaceOrderCommand("u2", "s2", "k3")).code())
                .isEqualTo(OrderResultCode.ACTIVITY_NOT_ACTIVE);
        assertThat(admission.snapshot("s2").remainingCapacity()).isEqualTo(1);
    }

    @Test
    void unpaidClosureReleasesConfirmedTokenButPaidOrderKeepsItConsumed() {
        fixture().activeSku("a1", "s1", 2);
        ready("s1", 2);
        OrderResult unpaid = orders.place(new PlaceOrderCommand("u1", "s1", "k1"));
        OrderResult paid = orders.place(new PlaceOrderCommand("u2", "s1", "k2"));
        payments.apply(new PaymentCallbackCommand("e1", "tx1", paid.orderId(),
                new BigDecimal("99.00"), "CNY", LocalDateTime.now()));

        jdbc.update("UPDATE orders SET expires_at = DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE id = ?",
                unpaid.orderId());
        assertThat(expiration.expireBatch()).isEqualTo(1);

        AdmissionGenerationSnapshot snapshot = admission.snapshot("s1");
        assertThat(snapshot.remainingCapacity()).isEqualTo(1);
        assertThat(snapshot.confirmed()).isEqualTo(1);
        assertThat(snapshot.released()).isEqualTo(1);
        assertThat(orders.place(new PlaceOrderCommand("u1", "s1", "k3")).code())
                .isEqualTo(OrderResultCode.CREATED);
    }

    @Test
    void concurrentSameKeyAndSameUserConvergeOnOneCommittedEffect() throws Exception {
        fixture().activeSku("a1", "s1", 2);
        ready("s1", 2);
        try (var executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            java.util.List<Future<OrderResult>> sameKey = new java.util.ArrayList<>();
            for (int index = 0; index < 8; index++) {
                sameKey.add(executor.submit(() -> {
                    start.await();
                    return orders.place(new PlaceOrderCommand("u1", "s1", "same-key"));
                }));
            }
            start.countDown();
            for (Future<OrderResult> result : sameKey) {
                assertThat(result.get().code()).isIn(
                        OrderResultCode.CREATED, OrderResultCode.RETRYABLE_CONTENTION);
            }
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE activity_sku_id = 's1'", Integer.class))
                .isEqualTo(1);
        assertThat(admission.snapshot("s1").confirmed()).isEqualTo(1);

        fixture().activeSku("a2", "s2", 2);
        ready("s2", 2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<OrderResult> first = executor.submit(() -> {
                start.await();
                return orders.place(new PlaceOrderCommand("u2", "s2", "key-a"));
            });
            Future<OrderResult> second = executor.submit(() -> {
                start.await();
                return orders.place(new PlaceOrderCommand("u2", "s2", "key-b"));
            });
            start.countDown();
            assertThat(java.util.List.of(first.get().code(), second.get().code()))
                    .contains(OrderResultCode.CREATED)
                    .allMatch(code -> code == OrderResultCode.CREATED
                            || code == OrderResultCode.RETRYABLE_CONTENTION
                            || code == OrderResultCode.EXISTING_EFFECTIVE_ORDER);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE activity_sku_id = 's2'", Integer.class))
                .isEqualTo(1);
        assertThat(admission.snapshot("s2").confirmed()).isEqualTo(1);
    }

    private void ready(String skuId, int capacity) {
        String fence = UUID.randomUUID().toString();
        assertThat(admission.beginGeneration(skuId, "g1", capacity, fence)).isTrue();
        assertThat(admission.publishGeneration(skuId, "g1", fence)).isTrue();
    }
}
