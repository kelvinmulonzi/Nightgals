package com.nightgals.profile.dto;

import com.nightgals.profile.Gender;
import com.nightgals.profile.Profile;
import com.nightgals.profile.Vibe;
import com.nightgals.user.VerificationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "A member profile")
public record ProfileResponse(
        UUID id,
        UUID userId,

        @Schema(description = "The member's public handle. This is what other members see.",
                example = "VelvetFalcon482")
        String username,

        @Schema(description = """
                Private nickname. Returned only to the owner and to staff - it is
                null on any view of another member.
                """)
        String displayName,

        String bio,

        @Schema(description = "Owner and staff only; null on another member's profile")
        LocalDate dateOfBirth,

        int age,
        Gender gender,
        String city,
        String country,
        Vibe vibe,
        boolean discoverable,

        @Schema(description = """
                What a viewer pays to unlock everything this creator has posted, in minor
                units. Always populated - a creator who has not named a price is sold at
                the platform default.
                """, example = "15000")
        long unlockPriceMinor,

        @Schema(example = "150.00") String unlockPriceDisplay,
        @Schema(example = "KES") String currency,

        @Schema(description = "False when the price above is the platform default rather than one this creator chose")
        boolean unlockPriceCustom,

        @Schema(description = "Whether this member has passed ID verification")
        VerificationStatus verificationStatus,

        Instant createdAt,
        Instant updatedAt) {

    /**
     * What a creator charges, resolved.
     *
     * @param effectiveMinor the price actually charged - hers, or the platform default
     * @param custom         whether she set it herself
     */
    public record Pricing(long effectiveMinor, boolean custom, String currency) {
    }

    /** Full view, for the owner or staff. */
    public static ProfileResponse of(Profile profile, Pricing pricing) {
        return build(profile, pricing, profile.getDisplayName(), profile.getDateOfBirth());
    }

    /**
     * What another member sees.
     *
     * <p>Deliberately withholds both the private nickname and the exact date of
     * birth. A verified account is not a publicly identified one: the platform
     * knows who someone is, the rest of the app knows them by their handle.
     *
     * <p>The price is <em>not</em> withheld - it is the point of the page.
     */
    public static ProfileResponse publicView(Profile profile, Pricing pricing) {
        return build(profile, pricing, null, null);
    }

    private static ProfileResponse build(Profile profile, Pricing pricing,
                                         String displayName, LocalDate dateOfBirth) {
        return new ProfileResponse(
                profile.getId(),
                profile.getUser().getId(),
                profile.getUser().getUsername(),
                displayName,
                profile.getBio(),
                dateOfBirth,
                profile.getAge(),
                profile.getGender(),
                profile.getCity(),
                profile.getCountry(),
                profile.getVibe(),
                profile.isDiscoverable(),
                pricing.effectiveMinor(),
                String.format("%.2f", pricing.effectiveMinor() / 100.0),
                pricing.currency(),
                pricing.custom(),
                profile.getUser().getVerificationStatus(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }
}
