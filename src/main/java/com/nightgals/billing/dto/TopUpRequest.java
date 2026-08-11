package com.nightgals.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Loading a balance to send gifts from")
public record TopUpRequest(

        @Schema(description = """
                How much to load, in the currency's minor unit. Must fall between the
                bounds reported by `GET /api/v1/billing/credit`; the presets there are
                the amounts worth offering as one tap.""",
                example = "5000")
        @NotNull(message = "Choose an amount")
        @Min(value = 1, message = "Amount must be positive")
        Long amountMinor,

        @Schema(description = "MOMO, STRIPE or CARD. Omitted uses the deployment default.",
                example = "STRIPE")
        @Size(max = 30)
        String method,

        @Schema(description = "Required for Mobile Money: the handset to prompt, no leading plus",
                example = "237689686224")
        @Pattern(regexp = "^[0-9]{6,20}$", message = "Digits only, no leading plus")
        String payerMsisdn) {
}
