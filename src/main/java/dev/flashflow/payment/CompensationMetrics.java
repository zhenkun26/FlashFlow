package dev.flashflow.payment;

import dev.flashflow.payment.persistence.PaymentMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public final class CompensationMetrics {
    private final MeterRegistry registry;
    private final PaymentMapper paymentMapper;

    public CompensationMetrics(MeterRegistry registry, PaymentMapper paymentMapper) {
        this.registry = registry;
        this.paymentMapper = paymentMapper;
    }

    @PostConstruct
    void registerGauge() {
        Gauge.builder("flashflow.compensation.open", paymentMapper, PaymentMapper::countOpenCompensationCases)
                .description("Open manual compensation cases")
                .register(registry);
    }
}
