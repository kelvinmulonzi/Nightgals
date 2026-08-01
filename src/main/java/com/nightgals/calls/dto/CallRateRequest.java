package com.nightgals.calls.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Price one call length, or withdraw it")
public record CallRateRequest(

        @Schema(description = "One of the platform's allowed lengths", example = "15",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Integer durationMinutes,

        @Schema(description = """
                What a call of this length costs, in minor units. Null withdraws the
                length - you stop offering it, without disturbing bookings already made.
                """, example = "8000")
        @Min(value = 0, message = "A price cannot be negative")
        Long priceMinor) {
}
