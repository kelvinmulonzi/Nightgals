package com.nightgals.earnings.dto;

import com.nightgals.earnings.PayoutAccount;
import com.nightgals.earnings.PayoutMethod;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The creator's payout account, with the destination masked")
public record PayoutAccountResponse(
        PayoutMethod method,
        @Schema(description = "Masked; only the last 4 characters are shown", example = "********5678")
        String destination,
        String accountName,
        String bankName) {

    public static PayoutAccountResponse of(PayoutAccount account) {
        return new PayoutAccountResponse(
                account.getMethod(),
                account.maskedDestination(),
                account.getAccountName(),
                account.getBankName());
    }
}
