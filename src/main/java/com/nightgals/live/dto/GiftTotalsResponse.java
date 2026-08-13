package com.nightgals.live.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How much has been gifted, for one account, in both directions.
 *
 * <p>Both sides in one answer because most accounts are only ever one of them,
 * and a viewer who later starts creating should not have to be moved to a
 * different screen to see the other number.
 */
@Schema(description = "What this account has gifted and been gifted, all time")
public record GiftTotalsResponse(

        @Schema(description = "What they have spent on gifts, in minor units", example = "4500")
        long sentMinor,

        @Schema(example = "4500") String sentDisplay,

        @Schema(description = "How many gifts they have sent", example = "6")
        long sentCount,

        @Schema(description = """
                What has been sent to them, gross - before the platform's commission.
                What a creator actually keeps is on her earnings ledger; this is the
                headline figure, not a withdrawable balance.
                """, example = "25000")
        long receivedMinor,

        @Schema(example = "25000") String receivedDisplay,

        @Schema(description = "How many gifts they have received", example = "31")
        long receivedCount,

        @Schema(example = "XAF") String currency) {
}
