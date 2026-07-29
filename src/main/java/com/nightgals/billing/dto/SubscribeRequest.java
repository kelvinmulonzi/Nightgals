package com.nightgals.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Which plan to buy")
public record SubscribeRequest(
        @Schema(description = "A plan code from GET /api/v1/billing/plans", example = "MONTHLY",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String planCode) {
}
