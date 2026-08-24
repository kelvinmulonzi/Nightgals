package com.nightgals.stats.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Who signed up, and how far accounts get.
 *
 * <p>Two different questions in one response because they are read together:
 * the daily line says whether the platform is growing, the funnel says whether
 * the people arriving are getting anywhere. Either alone misleads - a rising
 * signup line means little if nobody completes verification.
 */
@Schema(description = "Signups over a window, and the all-time onboarding funnel.")
public record GrowthResponse(

        @Schema(description = "First day in the window, inclusive.", example = "2026-07-26")
        LocalDate from,

        @Schema(description = "Last day in the window, inclusive.", example = "2026-08-24")
        LocalDate to,

        @Schema(description = "Signups in the window, all account types.", example = "128")
        long signups,

        @Schema(description = """
                One point per day, oldest first, quiet days present as zeroes so a
                chart can plot straight from the array.
                """)
        List<SignupPoint> points,

        @Schema(description = """
                The creator pipeline, all-time and unwindowed, widest first.

                Creators only: a viewer has no onboarding beyond browsing, so
                including them would show viewers dropping out of steps they were
                never asked to take. Each stage repeats the conditions of the ones
                before it, so the counts always narrow.
                """)
        List<FunnelStage> funnel,

        @Schema(description = "Account mix and sign-in methods, all-time.")
        Mix mix) {

    /** One day's signups, split by what the accounts are for. */
    @Schema(description = "A single day's signups.")
    public record SignupPoint(

            @Schema(description = "The day.", example = "2026-08-24")
            LocalDate date,

            @Schema(description = "Accounts that signed up to watch.", example = "7")
            long viewers,

            @Schema(description = "Accounts that signed up to post and earn.", example = "2")
            long creators,

            @Schema(description = "Both together.", example = "9")
            long total) {}

    /** One step of the creator pipeline. */
    @Schema(description = "One step on the way from signing up to publishing.")
    public record FunnelStage(

            @Schema(description = "Stable key the client maps to its own wording.",
                    example = "IDENTITY_APPROVED",
                    allowableValues = {"REGISTERED", "IDENTITY_SUBMITTED", "IDENTITY_APPROVED", "PUBLISHING"})
            String key,

            @Schema(description = "Accounts that reached at least this far.", example = "41")
            long count) {}

    /**
     * Composition rather than progress.
     *
     * <p>Separate from the funnel because these are not stages: an account is
     * not "further along" for being a creator, and a Google sign-in is a door
     * rather than a step. Putting them in the funnel would produce a chart whose
     * bars do not narrow, which is the one thing a funnel promises.
     */
    @Schema(description = "All-time account mix.")
    public record Mix(

            @Schema(description = "Accounts that watch.", example = "180")
            long viewers,

            @Schema(description = "Accounts that post.", example = "44")
            long creators,

            @Schema(description = "Accounts created through Google rather than a password.", example = "63")
            long viaGoogle,

            @Schema(description = "Accounts that have confirmed their email address.", example = "155")
            long emailVerified,

            @Schema(description = """
                    Viewers who have completed at least one purchase - the viewer-side
                    conversion the creator pipeline cannot show.
                    """, example = "37")
            long payingViewers) {}
}
