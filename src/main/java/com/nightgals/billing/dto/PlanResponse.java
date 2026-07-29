package com.nightgals.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;
import java.util.List;

@Schema(description = "What is on sale")
public record PlanResponse(
        @Schema(description = "False when monetisation is switched off entirely - everything is free")
        boolean monetisationEnabled,
        @Schema(example = "KES") String currency,
        @Schema(description = "One-off unlock of a single member") UnlockOption profileUnlock,
        @Schema(description = "Subscriptions unlocking every member") List<PlanOption> subscriptions) {

    @Schema(description = "Unlock one member's photos, video and live sessions")
    public record UnlockOption(
            @Schema(description = "Price in minor units, e.g. 10000 = KES 100.00", example = "10000")
            long priceMinor,
            @Schema(example = "100.00") String priceDisplay,
            @Schema(description = "How long the unlock lasts; null means forever", example = "PT720H")
            Duration duration) {
    }

    @Schema(description = "A subscription plan")
    public record PlanOption(
            @Schema(example = "MONTHLY") String code,
            @Schema(example = "1 month") String label,
            @Schema(example = "90000") long priceMinor,
            @Schema(example = "900.00") String priceDisplay,
            Duration duration) {
    }
}
