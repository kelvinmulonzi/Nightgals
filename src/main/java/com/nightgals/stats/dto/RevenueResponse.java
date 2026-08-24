package com.nightgals.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Settled money over a window, one series per currency.
 *
 * <p>Split by currency rather than summed into a single figure: the platform
 * quotes XAF and Stripe can settle in others, and adding those together
 * produces a number that means nothing. A caller that wants one line picks a
 * series; it never gets a total that silently mixed rates.
 */
@Schema(description = "Settled revenue over a window, one series per currency.")
public record RevenueResponse(

        @Schema(description = "First day in the window, inclusive.", example = "2026-07-26")
        LocalDate from,

        @Schema(description = "Last day in the window, inclusive.", example = "2026-08-24")
        LocalDate to,

        @Schema(description = "One entry per currency that saw money in the window. Empty if none did.")
        List<RevenueSeries> series) {

    /** Everything settled in one currency, with the daily breakdown behind it. */
    @Schema(description = "One currency's takings across the window.")
    public record RevenueSeries(

            @Schema(description = "ISO-4217 code.", example = "XAF")
            String currency,

            @Schema(description = """
                    Money actually collected, in minor units - the sticker price less
                    whatever was paid from credit. This is the figure that matches what
                    the payment providers moved.
                    """, example = "1250000")
            long cashMinor,

            @Schema(description = """
                    Sticker price of everything sold, in minor units, credit included.
                    Larger than `cashMinor` whenever balances were spent.
                    """, example = "1310000")
            long grossMinor,

            @Schema(description = "Settled purchases in the window.", example = "42")
            long orders,

            @Schema(description = """
                    One point per day, oldest first, gaps filled with zeroes so a chart
                    can plot straight from the array without inventing an axis.
                    """)
            List<RevenuePoint> points) {}

    /** One day. */
    @Schema(description = "A single day's takings.")
    public record RevenuePoint(

            @Schema(description = "The day these takings fall on.", example = "2026-08-24")
            LocalDate date,

            @Schema(description = "Collected that day, in minor units.", example = "45000")
            long cashMinor,

            @Schema(description = "Sold that day at sticker price, in minor units.", example = "48000")
            long grossMinor,

            @Schema(description = "Settled purchases that day.", example = "3")
            long orders) {}
}
