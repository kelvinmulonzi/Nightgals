package com.nightgals.referral.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A member's invite code, how it is doing, and what it has earned")
public record ReferralSummaryResponse(

        @Schema(description = "Fixed for the life of the account", example = "K7RBQ2XM")
        String code,

        @Schema(description = "The code wrapped in a link, ready to share",
                example = "https://noctyvera.com/join?ref=K7RBQ2XM")
        String shareLink,

        @Schema(description = "Accounts created with this code") long invited,

        @Schema(description = """
                How many of those went on to buy a package. Only these earned
                anything - the bonus is paid on a first purchase, not on a signup.
                """)
        long converted,

        @Schema(description = "Spendable credit, in minor units") long creditBalanceMinor,
        @Schema(example = "15000") String creditBalanceDisplay,

        @Schema(description = "What one conversion pays") long bonusPerReferralMinor,
        @Schema(example = "5000") String bonusPerReferralDisplay,

        @Schema(example = "XAF") String currency) {
}
