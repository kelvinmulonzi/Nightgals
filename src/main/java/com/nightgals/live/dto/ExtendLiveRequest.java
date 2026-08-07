package com.nightgals.live.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "Buy extra live minutes for today")
public record ExtendLiveRequest(

        @Schema(description = """
                How many extra minutes to buy. The server also enforces a daily
                ceiling, which is reported by `GET /api/v1/me/live/allowance`.
                """, example = "30", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Min(1) @Max(600) Integer minutes,

        @Schema(description = "Which payment method, from `GET /api/v1/billing/payment-methods`",
                example = "MOMO")
        String method,

        @Schema(description = "The Mobile Money number to charge, when the method needs one",
                example = "237689686224")
        @Pattern(regexp = "^\\+?[0-9][0-9 ()-]{7,19}$",
                message = "must be a phone number in international format")
        String payerMsisdn) {
}
