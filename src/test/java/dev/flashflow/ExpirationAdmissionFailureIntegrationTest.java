package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import dev.flashflow.admission.AdmissionDecision;
import dev.flashflow.admission.AdmissionGenerationSnapshot;
import dev.flashflow.admission.AdmissionGenerationState;
import dev.flashflow.admission.AdmissionResult;
import dev.flashflow.admission.MySqlOnlyAdmissionAdapter;
import dev.flashflow.expiration.ExpirationService;
import dev.flashflow.ordering.OrderApplicationService;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

class ExpirationAdmissionFailureIntegrationTest extends MySqlIntegrationTest {
    @Autowired private OrderApplicationService orders;
    @Autowired private ExpirationService expiration;
    @MockBean private MySqlOnlyAdmissionAdapter admission;

    @Test
    void ambiguousAfterCommitReleaseDoesNotUndoMysqlClosure() {
        when(admission.acquire(any())).thenAnswer(invocation -> {
            dev.flashflow.admission.AdmissionCommand command = invocation.getArgument(0);
            return new AdmissionResult(AdmissionDecision.BYPASSED, command.admissionId(), "g1");
        });
        when(admission.confirm(any(), anyString())).thenReturn(
                new dev.flashflow.admission.AdmissionLifecycleResult(
                        dev.flashflow.admission.AdmissionLifecycleDecision.CONFIRMED, "g1"));
        when(admission.snapshot(anyString())).thenReturn(new AdmissionGenerationSnapshot(
                "g1", AdmissionGenerationState.READY, 1, 0, 0, 1, 0, 0));
        when(admission.release(any(), anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("simulated Redis loss after MySQL commit"));

        fixture().activeSku("a1", "s1", 1);
        OrderResult order = orders.place(new PlaceOrderCommand("u1", "s1", "k1"));
        jdbc.update("UPDATE orders SET expires_at = DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE id = ?",
                order.orderId());

        assertThat(expiration.expireBatch()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.orderId()))
                .isEqualTo("CLOSED_UNPAID");
        assertThat(jdbc.queryForObject(
                "SELECT available_stock FROM activity_sku_stock WHERE id = 's1'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_claim", Integer.class)).isZero();
    }
}
