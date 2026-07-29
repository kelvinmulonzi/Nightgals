package com.nightgals.earnings.dto;

import com.nightgals.earnings.PayoutMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Where to send this creator's money")
public record PayoutAccountRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull PayoutMethod method,

        @Schema(description = "M-Pesa number in international format, or the bank account number",
                example = "254712345678", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 6, max = 60) String destination,

        @Schema(description = "The name the account is held in. Staff check this before sending money.",
                example = "Amina Wanjiru Kamau", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 150) String accountName,

        @Schema(description = "Required for BANK_TRANSFER", example = "Equity Bank")
        @Size(max = 120) String bankName) {
}
