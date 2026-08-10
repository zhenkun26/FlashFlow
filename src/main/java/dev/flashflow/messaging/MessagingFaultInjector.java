package dev.flashflow.messaging;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "flashflow.messaging", name = "mode", havingValue = "LIVE")
public final class MessagingFaultInjector {
    private final Fault fault;
    private final AtomicBoolean fired = new AtomicBoolean();
    private final AtomicBoolean recovered = new AtomicBoolean();
    private final AtomicReference<String> faultedIdentity = new AtomicReference<>();

    public MessagingFaultInjector(@Value("${flashflow.messaging.injected-fault:NONE}") String configured) {
        this.fault = Fault.valueOf(configured.trim().toUpperCase(Locale.ROOT));
    }

    void beforeConsume() {
        if (fault == Fault.BEFORE_CONSUME_ALWAYS) {
            throw new InjectedMessagingFault("before consumer delegate");
        }
    }

    void afterDurableResultBeforeAcknowledgement(String commandId, CommandConsumptionResult result) {
        if (fault == Fault.AFTER_DURABLE_RESULT_BEFORE_ACK_ONCE
                && commandId.equals(faultedIdentity.get())) {
            recovered.set(true);
        }
        if (fault == Fault.AFTER_DURABLE_RESULT_BEFORE_ACK_ONCE
                && result.status() == CommandStatus.COMPLETED
                && result.orderId() != null
                && fired.compareAndSet(false, true)) {
            faultedIdentity.set(commandId);
            throw new InjectedMessagingFault("after durable result before broker acknowledgement");
        }
    }

    void afterExpirationResultBeforeAcknowledgement(String orderId, ExpirationTriggerOutcome outcome) {
        if (fault == Fault.AFTER_EXPIRATION_RESULT_BEFORE_ACK_ONCE
                && orderId.equals(faultedIdentity.get())) {
            recovered.set(true);
        }
        if (fault == Fault.AFTER_EXPIRATION_RESULT_BEFORE_ACK_ONCE
                && outcome == ExpirationTriggerOutcome.CLOSED
                && fired.compareAndSet(false, true)) {
            faultedIdentity.set(orderId);
            throw new InjectedMessagingFault("after expiration result before broker acknowledgement");
        }
    }

    public boolean recoveredAfterFault() {
        return recovered.get();
    }

    public enum Fault {
        NONE,
        BEFORE_CONSUME_ALWAYS,
        AFTER_DURABLE_RESULT_BEFORE_ACK_ONCE,
        AFTER_EXPIRATION_RESULT_BEFORE_ACK_ONCE
    }

    private static final class InjectedMessagingFault extends RuntimeException {
        private InjectedMessagingFault(String message) {
            super(message);
        }
    }
}
