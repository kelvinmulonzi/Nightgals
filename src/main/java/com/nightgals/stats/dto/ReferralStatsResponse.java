package com.nightgals.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * How the referral programme is doing, and who is driving it.
 *
 * <p>Each referrer already sees their own numbers; this is the same picture for
 * everyone at once, so staff do not have to ask for screenshots of it.
 *
 * <p>"Counted" means the same thing here as on a referrer's own page: a referred
 * creator whose profile is complete. The wider sign-up number is shown beside it
 * rather than instead of it, because the gap between the two is the part worth
 * reading - accounts that arrived through a link and then stalled.
 */
@Schema(description = "Referral sign-ups in a window, and a leaderboard of referrers")
public record ReferralStatsResponse(

        LocalDate from,
        LocalDate to,

        @Schema(description = "Accounts created through a referral link in the window", example = "71")
        long signups,

        @Schema(description = "Of those, creators with a complete profile - what a referrer's own page counts",
                example = "30")
        long counted,

        @Schema(description = "Of those, creators who have not finished their profile", example = "16")
        long pending,

        @Schema(description = "Of those, viewer accounts, which never count", example = "25")
        long viewers,

        @Schema(description = "Referral bonuses paid in the window", example = "2")
        long converted,

        @Schema(description = "Credit paid out as referral bonuses in the window, in minor units", example = "10000")
        long creditPaidMinor,

        @Schema(example = "XAF")
        String currency,

        @Schema(description = "One point per day, quiet days included as zeroes")
        List<DailyPoint> points,

        @Schema(description = "Everyone who has ever referred an account, best in the window first")
        List<Referrer> referrers) {

    public record DailyPoint(LocalDate date, long signups, long counted) {
    }

    /**
     * One referrer's results.
     *
     * <p>The window columns answer "how are they doing lately"; {@code allTimeCounted}
     * is the figure on their own referrals page, so the two can be checked against
     * each other.
     */
    public record Referrer(
            UUID userId,
            String username,
            String email,
            String code,
            long signups,
            long counted,
            long pending,
            long viewers,
            long converted,
            long creditPaidMinor,
            @Schema(description = "Counted referrals over all time - the number on their own page")
            long allTimeCounted,
            @Schema(description = "When somebody last signed up with their code, in any window")
            Instant lastSignupAt) {
    }
}
