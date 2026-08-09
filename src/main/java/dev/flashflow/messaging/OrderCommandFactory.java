package dev.flashflow.messaging;

import dev.flashflow.admission.AdmissionIdentity;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.shared.RequestHash;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
public final class OrderCommandFactory {
    private static final String OPERATION = "CREATE_ORDER";
    private final AdmissionIdentity identities;
    private final Clock clock;

    public OrderCommandFactory(AdmissionIdentity identities, Clock clock) {
        this.identities = identities;
        this.clock = clock;
    }

    public OrderCommandEnvelope create(PlaceOrderCommand command, String traceId) {
        return new OrderCommandEnvelope(OrderCommandEnvelope.CURRENT_SCHEMA_VERSION,
                identities.admissionId(OPERATION, command.userId(), command.idempotencyKey()),
                command.userId(), command.activitySkuId(), command.idempotencyKey(),
                RequestHash.order(command.userId(), command.activitySkuId()), clock.instant(), traceId);
    }
}
