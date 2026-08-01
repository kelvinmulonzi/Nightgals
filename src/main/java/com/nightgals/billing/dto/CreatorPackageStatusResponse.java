package com.nightgals.billing.dto;

import com.nightgals.billing.CreatorPackageCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * A creator's publishing rights and how much of them is left.
 *
 * <p>The one call the studio makes to decide whether to show an upload button, a
 * "you are full" notice, or a pricing page.
 */
@Schema(description = "What the caller may currently publish and broadcast")
public record CreatorPackageStatusResponse(

        @Schema(description = "False when this deployment does not charge creators to post")
        boolean packagesRequired,

        @Schema(description = "True while anything - a package or the free trial - covers them")
        boolean active,

        @Schema(description = """
                True when that cover is the 7-day free trial rather than a package.
                Everything is unmetered while it runs, and stops when it ends.
                """)
        boolean onTrial,
        @Schema(description = "When the free trial runs out") Instant trialEndsAt,

        @Schema(description = "The package held, or null on a trial or with none")
        CreatorPackageCode code,
        @Schema(example = "Black Diamond") String label,
        @Schema(description = "When the package lapses") Instant expiresAt,

        @Schema(description = "Photos allowed at once") int photoLimit,
        int photosUsed,
        int photosRemaining,

        @Schema(description = "Premium videos allowed at once. Free ones are not metered.")
        int videoLimit,
        int videosUsed,
        int videosRemaining,

        @Schema(description = "Minutes of live per day. Zero means live is not available.")
        int liveMinutesPerDay,

        @Schema(description = "Placement in search. Higher wins; zero is unranked.")
        int searchPriority,

        @Schema(description = "True when a photo can be uploaded right now") boolean canPostPhotos,
        @Schema(description = "True when a premium video can be uploaded right now") boolean canPostVideos,
        @Schema(description = "True when the caller may broadcast at all") boolean canGoLive,

        @Schema(description = "Everything on sale, so the studio can render an upgrade path")
        List<CreatorPackageResponse> available) {

    /** Nothing bought and no trial left: publishing is blocked. */
    public static CreatorPackageStatusResponse none(int photosUsed, int videosUsed,
                                                    List<CreatorPackageResponse> available) {
        return new CreatorPackageStatusResponse(
                true, false, false, null, null, null, null,
                0, photosUsed, 0,
                0, videosUsed, 0,
                0, 0,
                false, false, false,
                available);
    }

    /** The first seven days: everything works, nothing is metered. */
    public static CreatorPackageStatusResponse onTrial(Instant trialEndsAt, int photosUsed,
                                                       int videosUsed,
                                                       List<CreatorPackageResponse> available) {
        return new CreatorPackageStatusResponse(
                true, true, true, trialEndsAt, null, "Free trial", trialEndsAt,
                // Unmetered, so the limits are reported as zero and "remaining" as
                // -1 to mean "no ceiling" rather than "none left".
                0, photosUsed, -1,
                0, videosUsed, -1,
                -1, 1,
                true, true, true,
                available);
    }

    public static CreatorPackageStatusResponse active(CreatorPackageCode code, String label,
                                                      int photoLimit, int photosUsed,
                                                      int videoLimit, int videosUsed,
                                                      int liveMinutesPerDay, int searchPriority,
                                                      Instant expiresAt,
                                                      List<CreatorPackageResponse> available) {
        int photosLeft = Math.max(0, photoLimit - photosUsed);
        int videosLeft = Math.max(0, videoLimit - videosUsed);
        return new CreatorPackageStatusResponse(
                true, true, false, null, code, label, expiresAt,
                photoLimit, photosUsed, photosLeft,
                videoLimit, videosUsed, videosLeft,
                liveMinutesPerDay, searchPriority,
                photoLimit > 0 && photosLeft > 0,
                videoLimit > 0 && videosLeft > 0,
                liveMinutesPerDay > 0,
                available);
    }

    /** Packages switched off: a flat allowance, and nothing to buy. */
    public static CreatorPackageStatusResponse unmetered(int photosUsed, int photoLimit,
                                                         int videosUsed, int videoLimit,
                                                         int liveMinutes) {
        int photosLeft = Math.max(0, photoLimit - photosUsed);
        int videosLeft = Math.max(0, videoLimit - videosUsed);
        return new CreatorPackageStatusResponse(
                false, true, false, null, null, "Included", null,
                photoLimit, photosUsed, photosLeft,
                videoLimit, videosUsed, videosLeft,
                liveMinutes, 0,
                photosLeft > 0, videosLeft > 0, true,
                List.of());
    }
}
