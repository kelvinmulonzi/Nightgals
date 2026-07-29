package com.nightgals.earnings.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A creator's money, at a glance")
public record EarningsSummaryResponse(
        @Schema(example = "KES") String currency,
        @Schema(description = "Payable now", example = "70000") long availableMinor,
        @Schema(description = "Earned but still inside the hold period") long pendingMinor,
        @Schema(description = "Attached to a payout being processed") long reservedMinor,
        @Schema(description = "Already paid out") long paidMinor,
        @Schema(description = "Everything ever earned, net of commission") long lifetimeMinor,
        @Schema(description = "Smallest payout the platform will process") long minimumPayoutMinor,
        @Schema(description = "True when a payout can be requested right now") boolean canRequestPayout,
        @Schema(description = "True while a payout is already being processed") boolean payoutInProgress,
        @Schema(description = "False until the creator has told us where to send money")
        boolean payoutAccountSet) {
}
