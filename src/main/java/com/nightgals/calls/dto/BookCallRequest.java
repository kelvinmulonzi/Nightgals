package com.nightgals.calls.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

@Schema(description = "Book a private call")
public record BookCallRequest(

        @Schema(description = "One of the lengths this creator offers", example = "15",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Integer durationMinutes,

        @Schema(description = "When it should start", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Future Instant scheduledFor) {
}
