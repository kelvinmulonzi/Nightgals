package com.nightgals.calls.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

@Schema(description = "Book a private call")
public record BookCallRequest(

        @Schema(description = "One of the lengths this creator offers", example = "15",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Integer durationMinutes,

        @Schema(description = "When it should start", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Future Instant scheduledFor,

        @Schema(description = """
                The Mobile Money number to charge, in international format. Required
                when the platform is on a mobile-money provider, ignored otherwise.
                """, example = "237689686224")
        @Pattern(regexp = "^\\+?[0-9][0-9 ()-]{7,19}$",
                message = "must be a phone number in international format")
        String payerMsisdn) {
}
