package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flashflow.admission.AdmissionCommand;
import dev.flashflow.admission.AdmissionDecision;
import dev.flashflow.admission.AdmissionIdentity;
import dev.flashflow.admission.AdmissionLifecycleDecision;
import dev.flashflow.admission.AdmissionLifecycleResult;
import dev.flashflow.admission.AdmissionPort;
import dev.flashflow.admission.AdmissionResult;
import dev.flashflow.inventory.InventoryStrategyRegistry;
import dev.flashflow.inventory.persistence.InventoryMapper;
import dev.flashflow.ordering.NoOpOrderingTransactionHook;
import dev.flashflow.ordering.OrderApplicationService;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.OrderResultCode;
import dev.flashflow.ordering.OrderingTransactionHook;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.ordering.persistence.OrderMapper;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.FlashFlowProperties;
import dev.flashflow.support.MySqlIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

class AdmissionCrossStoreFailureIntegrationTest extends MySqlIntegrationTest {
    @Autowired private OrderMapper orderMapper;
    @Autowired private InventoryMapper inventoryMapper;
    @Autowired private InventoryStrategyRegistry registry;
    @Autowired private FlashFlowMetrics metrics;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void unavailableAdmissionFailsClosedBeforeMySqlTransaction() {
        fixture().activeSku("a1", "s1", 1);
        RecordingAdmission admission = new RecordingAdmission(
                AdmissionDecision.UNAVAILABLE, AdmissionLifecycleDecision.UNAVAILABLE);

        OrderResult result = service(admission, new NoOpOrderingTransactionHook())
                .place(new PlaceOrderCommand("u1", "s1", "k1"));

        assertThat(result.code()).isEqualTo(OrderResultCode.RETRYABLE_CONTENTION);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
        assertThat(admission.confirms + admission.releases + admission.quarantines).isZero();
    }

    @Test
    void mysqlRejectionAfterAdmissionSafelyReleasesToken() {
        fixture().inactiveSku("a1", "s1", 1);
        RecordingAdmission admission = new RecordingAdmission(AdmissionLifecycleDecision.RELEASED);

        OrderResult result = service(admission, new NoOpOrderingTransactionHook())
                .place(new PlaceOrderCommand("u1", "s1", "k1"));

        assertThat(result.code()).isEqualTo(OrderResultCode.ACTIVITY_NOT_ACTIVE);
        assertThat(admission.releases).isEqualTo(1);
        assertThat(admission.confirms).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    void mysqlCommitSurvivesAmbiguousRedisConfirmation() {
        fixture().activeSku("a1", "s1", 1);
        RecordingAdmission admission = new RecordingAdmission(AdmissionLifecycleDecision.AMBIGUOUS);

        OrderResult result = service(admission, new NoOpOrderingTransactionHook())
                .place(new PlaceOrderCommand("u1", "s1", "k1"));

        assertThat(result.code()).isEqualTo(OrderResultCode.CREATED);
        assertThat(admission.confirms).isEqualTo(1);
        assertThat(admission.quarantines).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT available_stock FROM activity_sku_stock WHERE id='s1'", Integer.class)).isZero();
    }

    @Test
    void unknownMysqlOutcomeIsQuarantinedWithoutUnsafeCapacityReturn() {
        fixture().activeSku("a1", "s1", 1);
        RecordingAdmission admission = new RecordingAdmission(AdmissionLifecycleDecision.QUARANTINED);
        OrderingTransactionHook crash = command -> { throw new SimulatedUnknownOutcome(); };

        assertThatThrownBy(() -> service(admission, crash)
                .place(new PlaceOrderCommand("u1", "s1", "k1")))
                .isInstanceOf(SimulatedUnknownOutcome.class);

        assertThat(admission.quarantines).isEqualTo(1);
        assertThat(admission.releases).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT available_stock FROM activity_sku_stock WHERE id='s1'", Integer.class)).isEqualTo(1);
    }

    private OrderApplicationService service(AdmissionPort admission, OrderingTransactionHook hook) {
        FlashFlowProperties properties = properties();
        return new OrderApplicationService(orderMapper, inventoryMapper, registry, properties,
                Clock.systemUTC(), metrics, transactionManager, hook, admission,
                new AdmissionIdentity(properties));
    }

    private static FlashFlowProperties properties() {
        return new FlashFlowProperties(
                new FlashFlowProperties.Inventory(FlashFlowProperties.Strategy.CONDITIONAL_ATOMIC, 3),
                new FlashFlowProperties.Ordering(
                        3, 1, FlashFlowProperties.TransactionSequence.STOCK_FIRST),
                new FlashFlowProperties.Expiration(Duration.ofMinutes(10), 20, false),
                new FlashFlowProperties.Admission(
                        FlashFlowProperties.AdmissionMode.REDIS_LUA, Duration.ofSeconds(30), "v2-1",
                        "integration-test-admission-secret-32-characters"));
    }

    private static final class RecordingAdmission implements AdmissionPort {
        private final AdmissionDecision acquireDecision;
        private final AdmissionLifecycleDecision lifecycleDecision;
        private int confirms;
        private int releases;
        private int quarantines;

        private RecordingAdmission(AdmissionLifecycleDecision lifecycleDecision) {
            this(AdmissionDecision.ADMITTED, lifecycleDecision);
        }

        private RecordingAdmission(
                AdmissionDecision acquireDecision, AdmissionLifecycleDecision lifecycleDecision) {
            this.acquireDecision = acquireDecision;
            this.lifecycleDecision = lifecycleDecision;
        }

        @Override
        public AdmissionResult acquire(AdmissionCommand command) {
            return new AdmissionResult(acquireDecision, command.admissionId(), "g1");
        }

        @Override
        public AdmissionLifecycleResult confirm(AdmissionCommand command, String generation) {
            confirms++;
            return new AdmissionLifecycleResult(lifecycleDecision, generation);
        }

        @Override
        public AdmissionLifecycleResult release(
                AdmissionCommand command, String generation, boolean confirmedClosure) {
            releases++;
            return new AdmissionLifecycleResult(lifecycleDecision, generation);
        }

        @Override
        public AdmissionLifecycleResult quarantine(AdmissionCommand command, String generation) {
            quarantines++;
            return new AdmissionLifecycleResult(lifecycleDecision, generation);
        }
    }

    private static final class SimulatedUnknownOutcome extends RuntimeException {
    }
}
