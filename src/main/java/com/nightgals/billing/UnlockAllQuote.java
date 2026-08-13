package com.nightgals.billing;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What it costs to open everything a creator has locked right now.
 *
 * <p>Answered per viewer, not per creator: it covers what <em>this</em> viewer
 * cannot already see, so somebody who has bought two clips is quoted for the
 * rest rather than for the gallery from scratch.
 */
@Schema(description = "One payment for every locked item this creator has posted so far")
public record UnlockAllQuote(

        @Schema(description = "How many locked items the payment would open", example = "4")
        int itemCount,

        @Schema(description = "How many of those are photos", example = "2")
        int photoCount,

        @Schema(description = "How many of those are videos", example = "2")
        int videoCount,

        @Schema(description = """
                The total, in minor units: the sum of what each item costs on its own.
                There is no bundle discount - buying the lot costs what buying them one
                at a time costs, and simply takes one payment instead of four.
                """, example = "8000")
        long totalMinor,

        @Schema(example = "8000") String totalDisplay,

        String currency) {

    /** True when there is anything left to sell this viewer. */
    public boolean available() {
        return itemCount > 0;
    }
}
