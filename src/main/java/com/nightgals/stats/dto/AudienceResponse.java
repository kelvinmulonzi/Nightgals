package com.nightgals.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Who is actually being looked at.
 *
 * <p>Revenue says what people paid for; this says what they looked at, which is
 * the half of the funnel that comes first and the one nothing on the console
 * could see. A creator with a thousand views and no sales is a pricing problem;
 * one with no views is a discovery problem, and the two need opposite fixes.
 */
@Schema(description = "Views across profiles, videos and reels")
public record AudienceResponse(

        LocalDate from,
        LocalDate to,

        @Schema(description = "Every view in the window, all three kinds together", example = "8421")
        long totalViews,

        @Schema(description = "One point per day, split by what was looked at")
        List<DailyPoint> points,

        @Schema(description = "The most-looked-at profiles in the window, best first")
        List<TopProfile> topProfiles) {

    public record DailyPoint(LocalDate date, long profiles, long media, long reels, long total) {
    }

    /**
     * A creator and how much attention she got.
     *
     * <p>Carries the sales alongside deliberately. Views on their own rank the
     * loudest, not the best earners, and the interesting rows are the ones where
     * the two numbers disagree.
     */
    public record TopProfile(
            UUID userId,
            String username,
            String displayName,
            long views,
            @Schema(description = "Completed purchases of anything of hers, in the same window")
            long sales) {
    }
}
