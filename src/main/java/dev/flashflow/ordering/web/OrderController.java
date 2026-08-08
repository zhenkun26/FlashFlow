package dev.flashflow.ordering.web;

import dev.flashflow.ordering.OrderApplicationService;
import dev.flashflow.ordering.OrderQueryService;
import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.OrderResultCode;
import dev.flashflow.ordering.OrderSummary;
import dev.flashflow.ordering.PlaceOrderCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderApplicationService orderService;
    private final OrderQueryService queryService;

    public OrderController(OrderApplicationService orderService, OrderQueryService queryService) {
        this.orderService = orderService;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<OrderResult> place(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        MDC.put("idempotencyKey", idempotencyKey);
        MDC.put("userId", request.userId());
        MDC.put("activitySkuId", request.activitySkuId());
        OrderResult result = orderService.place(
                new PlaceOrderCommand(request.userId(), request.activitySkuId(), idempotencyKey));
        if (result.orderId() != null) {
            MDC.put("orderId", result.orderId());
        }
        return response(result);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderSummary> find(@PathVariable @NotBlank @Size(max = 36) String orderId) {
        OrderSummary summary = queryService.find(orderId);
        return summary == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(summary);
    }

    private static ResponseEntity<OrderResult> response(OrderResult result) {
        if (result.code() == OrderResultCode.CREATED) {
            return ResponseEntity.created(URI.create("/api/v1/orders/" + result.orderId())).body(result);
        }
        HttpStatus status = switch (result.code()) {
            case SOLD_OUT, ACTIVITY_NOT_ACTIVE, EXISTING_EFFECTIVE_ORDER -> HttpStatus.CONFLICT;
            case IDEMPOTENCY_CONFLICT, INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case RETRYABLE_CONTENTION -> HttpStatus.SERVICE_UNAVAILABLE;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(result);
    }
}

