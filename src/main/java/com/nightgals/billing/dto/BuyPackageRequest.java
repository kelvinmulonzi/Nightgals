package com.nightgals.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "Which package to buy")
public record BuyPackageRequest(
        @Schema(description = "PRO, DIAMOND or BLACK_DIAMOND", example = "DIAMOND",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String packageCode,

        @Schema(description = """
                The Mobile Money number to charge, in international format. Required
                when the platform is on a mobile-money provider, ignored otherwise.
                """, example = "237689686224")
        @Pattern(regexp = "^\\+?[0-9][0-9 ()-]{7,19}$",
                message = "must be a phone number in international format")
        String payerMsisdn) {
}
