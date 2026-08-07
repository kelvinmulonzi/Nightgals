package com.nightgals.live.dto;

import com.nightgals.common.Money;
import com.nightgals.config.MonetizationProperties;
import com.nightgals.live.LiveQuotaService;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Today's live allowance, and what more of it costs")
public record LiveAllowanceResponse(

        @Schema(description = "Total minutes available today: the package allowance plus anything bought",
                example = "45")
        int allowanceMinutes,

        @Schema(description = "The bought part of the allowance", example = "15")
        int boughtMinutes,

        @Schema(description = "Minutes used today, including a broadcast on air right now", example = "40")
        int usedMinutes,

        @Schema(description = "What is left. Zero means the next broadcast is refused.", example = "5")
        int remainingMinutes,

        @Schema(description = "Whether more minutes can be bought at all on this deployment")
        boolean extendable,

        @Schema(description = "What one extra minute costs, in minor units. Null when not extendable.",
                example = "200")
        Long pricePerMinuteMinor,

        @Schema(example = "200") String pricePerMinuteDisplay,

        @Schema(description = "The most that may be bought in one day, across all top-ups", example = "120")
        int maxMinutesPerDay,

        @Schema(example = "XAF") String currency) {

    public static LiveAllowanceResponse of(LiveQuotaService.Remaining remaining,
                                           MonetizationProperties.LiveExtension rules,
                                           String currency) {
        boolean extendable = rules != null && rules.pricePerMinuteMinor() > 0;
        return new LiveAllowanceResponse(
                remaining.allowanceMinutes(),
                remaining.boughtMinutes(),
                remaining.usedMinutes(),
                remaining.remainingMinutes(),
                extendable,
                extendable ? rules.pricePerMinuteMinor() : null,
                extendable ? Money.plain(rules.pricePerMinuteMinor(), currency) : null,
                extendable ? rules.maxMinutesPerDay() : 0,
                currency);
    }
}
