package dev.flashflow.ordering.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlaceOrderRequest(
        @NotBlank @Size(max = 64) String userId,
        @NotBlank @Size(max = 64) String activitySkuId) {
}

