package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.inventory.ReservationStatus;
import dev.flashflow.ordering.OrderStatus;
import dev.flashflow.payment.CompensationStatus;
import org.junit.jupiter.api.Test;

class DomainStateTest {
    @Test
    void orderStateMachineAllowsOnlyPendingTerminalTransitions() {
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.CLOSED_UNPAID)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CLOSED_UNPAID)).isFalse();
        assertThat(OrderStatus.CLOSED_UNPAID.canTransitionTo(OrderStatus.PAID)).isFalse();
        assertThat(OrderStatus.PENDING_PAYMENT.isEffective()).isTrue();
        assertThat(OrderStatus.CLOSED_UNPAID.isEffective()).isFalse();
    }

    @Test
    void reservationStateMachineHasTwoTerminalStates() {
        assertThat(ReservationStatus.RESERVED.canTransitionTo(ReservationStatus.CONFIRMED)).isTrue();
        assertThat(ReservationStatus.RESERVED.canTransitionTo(ReservationStatus.RELEASED)).isTrue();
        assertThat(ReservationStatus.CONFIRMED.canTransitionTo(ReservationStatus.RELEASED)).isFalse();
        assertThat(ReservationStatus.RELEASED.canTransitionTo(ReservationStatus.CONFIRMED)).isFalse();
    }

    @Test
    void compensationCanOnlyMoveFromOpenToResolved() {
        assertThat(CompensationStatus.OPEN.canTransitionTo(CompensationStatus.RESOLVED)).isTrue();
        assertThat(CompensationStatus.RESOLVED.canTransitionTo(CompensationStatus.OPEN)).isFalse();
    }
}
