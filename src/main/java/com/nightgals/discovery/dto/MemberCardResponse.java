package com.nightgals.discovery.dto;

import com.nightgals.profile.Gender;
import com.nightgals.profile.Profile;
import com.nightgals.profile.Vibe;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = """
        The lean profile shown while scrolling the feed. Free to everyone who is
        verified. Enough to decide whether somebody is worth unlocking, and no more.
        """)
public record MemberCardResponse(
        UUID userId,
        @Schema(example = "VelvetFalcon482") String username,
        int age,
        Gender gender,
        String city,
        String country,
        Vibe vibe,
        String bio,

        @Schema(description = "Photos the creator marked FREE. Playable by anyone.")
        java.util.List<String> freePhotoUrls,

        @Schema(description = "Videos the creator marked FREE. Playable by anyone.")
        java.util.List<String> freeVideoUrls,

        @Schema(description = "Photos behind the paywall", example = "6") int lockedPhotoCount,
        @Schema(description = "Videos behind the paywall", example = "2") int lockedVideoCount,
        @Schema(description = "True if this member is broadcasting right now") boolean liveNow,

        @Schema(description = "True when the caller can already see everything - no payment needed")
        boolean unlocked,

        @Schema(description = """
                What unlocking this creator costs, in minor units. Her price if she set
                one, the platform default otherwise - so the card can show a real number
                without a second call.
                """, example = "15000")
        long unlockPriceMinor,

        @Schema(example = "150.00") String unlockPriceDisplay,
        @Schema(example = "KES") String currency) {

    public static MemberCardResponse of(Profile profile,
                                        java.util.List<String> freePhotoUrls,
                                        java.util.List<String> freeVideoUrls,
                                        int lockedPhotoCount,
                                        int lockedVideoCount,
                                        boolean liveNow,
                                        boolean unlocked,
                                        long unlockPriceMinor,
                                        String currency) {
        return new MemberCardResponse(
                profile.getUser().getId(),
                profile.getUser().getUsername(),
                profile.getAge(),
                profile.getGender(),
                profile.getCity(),
                profile.getCountry(),
                profile.getVibe(),
                profile.getBio(),
                freePhotoUrls,
                freeVideoUrls,
                lockedPhotoCount,
                lockedVideoCount,
                liveNow,
                unlocked,
                unlockPriceMinor,
                String.format("%.2f", unlockPriceMinor / 100.0),
                currency);
    }
}
