package dev.flashflow.payment.web;

import dev.flashflow.payment.PaymentApplicationService;
import dev.flashflow.payment.PaymentCallbackCommand;
import dev.flashflow.payment.PaymentResult;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local", "test", "lab"})
@RequestMapping("/api/v1/simulated-payments/callbacks")
public class SimulatedPaymentController {
    private final PaymentApplicationService paymentService;

    public SimulatedPaymentController(PaymentApplicationService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResult> callback(@Valid @RequestBody PaymentCallbackRequest request) {
        MDC.put("orderId", request.orderId());
        MDC.put("providerEventId", request.providerEventId());
        MDC.put("providerTransactionId", request.providerTransactionId());
        PaymentResult result = paymentService.apply(new PaymentCallbackCommand(
                request.providerEventId(), request.providerTransactionId(), request.orderId(),
                request.amount(), request.currency(), request.paidAt()));
        return ResponseEntity.ok(result);
    }
}

