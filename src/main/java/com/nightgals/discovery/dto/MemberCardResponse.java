package com.nightgals.discovery.dto;

import com.nightgals.profile.Gender;
import com.nightgals.profile.Profile;
import com.nightgals.profile.Vibe;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = """
        The lean profile shown while scrolling. Free to everyone, signed in or not.
        Enough to decide whether somebody is worth paying for, and no more.
        """)
public record MemberCardResponse(
        UUID userId,
        @Schema(example = "VelvetFalcon482") String username,

        @Schema(description = """
                The name shown under the picture. Null when the member has not set one,
                in which case the card falls back to the username.
                """, example = "Amina")
        String displayName,

        @Schema(description = """
                True when this member has passed the identity check. Always true on the
                feed as it stands - only approved profiles are listed - but carried so
                the badge renders off the fact rather than off that assumption.
                """)
        boolean verified,

        @Schema(description = """
                The photo this member chose to lead with. Null when she has not set
                one, in which case a client should fall back to the first free photo
                and then to a placeholder - never to a blank tile.
                """, example = "/api/v1/media/9e6764e7-.../file")
        String profilePhotoUrl,

        Integer age,
        Gender gender,
        String city,
        String country,
        Vibe vibe,
        String bio,

        @Schema(description = "Photos the caller can already see. Playable now.")
        List<String> freePhotoUrls,

        @Schema(description = "Videos the caller can already see. Playable now.")
        List<String> freeVideoUrls,

        @Schema(description = "Photos behind the paywall", example = "6") int lockedPhotoCount,
        @Schema(description = "Videos behind the paywall", example = "2") int lockedVideoCount,

        @Schema(description = """
                Every video on the profile, free and paid together - what the card says
                there is to watch.

                Deliberately not `lockedVideoCount`, which the card used to show: that
                counts only what this particular caller has not paid for, so the same
                creator advertised a different number of videos to different people, and
                zero to anyone who had bought them all.
                """, example = "8")
        int videoCount,
        @Schema(description = "True if this member is broadcasting right now") boolean liveNow,
        @Schema(description = "True if the caller follows her") boolean following,

        @Schema(description = """
                The cheapest locked item on this profile, in minor units - everything is
                priced per item now, so there is no single price for a person. Null when
                nothing of hers is locked to this caller.
                """, example = "2000")
        Long fromPriceMinor,
        @Schema(example = "2000") String fromPriceDisplay,

        @Schema(description = """
                Placement weight from her package: 3 Black Diamond, 2 Diamond, 1 Pro,
                0 unranked. The feed is already sorted by it; this is here so a client
                can badge the top tier rather than re-sort.
                """, example = "3")
        int searchPriority,

        @Schema(description = "People who have opened this profile, all time", example = "1284")
        long viewCount,

        @Schema(example = "XAF") String currency) {

    public static MemberCardResponse of(Profile profile,
                                        String profilePhotoUrl,
                                        List<String> freePhotoUrls,
                                        List<String> freeVideoUrls,
                                        int lockedPhotoCount,
                                        int lockedVideoCount,
                                        int videoCount,
                                        boolean liveNow,
                                        boolean following,
                                        Long fromPriceMinor,
                                        String fromPriceDisplay,
                                        int searchPriority,
                                        String currency) {
        return new MemberCardResponse(
                profile.getUser().getId(),
                profile.getUser().getUsername(),
                profile.getDisplayName(),
                // See ProfileResponse: the badge is a checked document, not an
                // approved account.
                profile.getUser().isIdentityVerified(),
                profilePhotoUrl,
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
                videoCount,
                liveNow,
                following,
                fromPriceMinor,
                fromPriceDisplay,
                searchPriority,
                // Read straight off the profile: the caller already handed us the
                // row, so asking it to pass the number as well is one more thing
                // to get wrong at each of the call sites.
                profile.getViewCount(),
                currency);
    }
}
