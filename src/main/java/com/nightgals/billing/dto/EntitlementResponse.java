package com.nightgals.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * What the caller has, in general.
 *
 * <p>Deliberately not a list of everything they own. Access is per item now, and
 * a viewer with two hundred unlocked videos does not want them enumerated on
 * every page load - each gallery reports its own `locked` flags instead.
 */
@Schema(description = "The caller's standing: trial, credit, and how much they own")
public record EntitlementResponse(

        @Schema(description = "True while the 7-day free trial is running - everything is open")
        boolean onTrial,
        @Schema(description = "When the trial ends") Instant trialEndsAt,

        @Schema(description = "How many items the caller has bought") long unlockedItems,

        @Schema(description = "Spendable referral credit, in minor units") long creditBalanceMinor,
        @Schema(example = "5000") String creditBalanceDisplay,
        @Schema(example = "XAF") String currency) {
}
