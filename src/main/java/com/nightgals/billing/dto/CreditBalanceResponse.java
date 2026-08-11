package com.nightgals.billing.dto;

import com.nightgals.common.Money;
import com.nightgals.config.MonetizationProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "The balance gifts are sent from, and what a top-up may be")
public record CreditBalanceResponse(

        @Schema(description = "What is on account now, in minor units", example = "12500")
        long balanceMinor,

        @Schema(example = "12500") String balanceDisplay,

        @Schema(description = "Whether balance can be bought at all on this deployment")
        boolean topUpAvailable,

        @Schema(description = "Smallest permitted top-up. Null when unavailable.", example = "1000")
        Long minTopUpMinor,

        @Schema(description = "Largest permitted top-up. Null when unavailable.", example = "500000")
        Long maxTopUpMinor,

        @Schema(description = "Amounts worth offering as one tap. Advisory - any value in range works.",
                example = "[1000, 5000, 10000, 25000]")
        List<Long> presetsMinor,

        @Schema(example = "XAF") String currency) {

    public static CreditBalanceResponse of(long balanceMinor,
                                           MonetizationProperties.CreditTopUp limits,
                                           String currency) {
        boolean available = limits != null;
        return new CreditBalanceResponse(
                balanceMinor,
                Money.plain(balanceMinor, currency),
                available,
                available ? limits.floor() : null,
                available ? limits.ceiling() : null,
                available ? limits.presets() : List.of(),
                currency);
    }
}
