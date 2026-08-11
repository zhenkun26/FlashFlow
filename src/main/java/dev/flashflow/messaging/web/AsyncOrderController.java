package dev.flashflow.messaging.web;

import dev.flashflow.messaging.AsyncOrderSubmission;
import dev.flashflow.messaging.AsyncOrderSubmissionService;
import dev.flashflow.messaging.CommandLedgerService;
import dev.flashflow.messaging.CommandSummary;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.ordering.web.PlaceOrderRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v2")
@ConditionalOnExpression("'${flashflow.messaging.mode:DISABLED}' == 'DIRECT' or '${flashflow.messaging.mode:DISABLED}' == 'OUTBOX'")
public class AsyncOrderController {
    private final AsyncOrderSubmissionService orders;
    private final CommandLedgerService ledger;

    public AsyncOrderController(AsyncOrderSubmissionService orders, CommandLedgerService ledger) {
        this.orders = orders;
        this.ledger = ledger;
    }

    @PostMapping("/orders")
    public ResponseEntity<AsyncOrderResponse> place(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        MDC.put("idempotencyKey", idempotencyKey);
        MDC.put("userId", request.userId());
        MDC.put("activitySkuId", request.activitySkuId());
        AsyncOrderSubmission result = orders.submit(
                new PlaceOrderCommand(request.userId(), request.activitySkuId(), idempotencyKey), traceId());
        AsyncOrderResponse body = new AsyncOrderResponse(
                result.commandId(), result.status(), result.statusLocation(), result.cause());
        return result.accepted()
                ? ResponseEntity.accepted().location(result.statusLocation()).body(body)
                : ResponseEntity.status(503).body(body);
    }

    @GetMapping("/order-commands/{commandId}")
    public ResponseEntity<CommandSummary> status(
            @PathVariable @NotBlank @Size(max = 64) String commandId,
            @RequestHeader("X-User-Id") @NotBlank @Size(max = 64) String callerId) {
        CommandSummary summary = ledger.summaryForCaller(commandId, callerId);
        return summary == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(summary);
    }

    private static String traceId() {
        String requestId = MDC.get("requestId");
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }
}
