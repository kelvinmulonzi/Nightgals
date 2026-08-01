package com.nightgals.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Which package to buy")
public record BuyPackageRequest(
        @Schema(description = "BRONZE, SILVER or GOLD", example = "GOLD",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String packageCode) {
}
