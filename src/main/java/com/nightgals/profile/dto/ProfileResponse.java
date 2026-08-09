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
                The name shown under the profile picture. Public - returned on every
                view, including anonymous ones.
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

        @Schema(description = "Whether this member has passed ID verification")
        VerificationStatus verificationStatus,

        @Schema(description = "True when verificationStatus is APPROVED. The badge renders off this.")
        boolean verified,

        @Schema(description = "Optional public contact number, for a WhatsApp link. Null when not shared.",
                example = "237689686224")
        String whatsappNumber,

        @Schema(description = """
                The profile picture, as a path to fetch. Null when none is set - show
                a placeholder rather than a broken image.
                """, example = "/api/v1/media/9e6764e7-.../file")
        String profilePhotoUrl,

        Instant createdAt,
        Instant updatedAt) {

    /** Full view, for the owner or staff. */
    public static ProfileResponse of(Profile profile) {
        return of(profile, null);
    }

    public static ProfileResponse of(Profile profile, String profilePhotoUrl) {
        return build(profile, profile.getDisplayName(), profile.getDateOfBirth(), profilePhotoUrl);
    }

    /**
     * What another member sees.
     *
     * <p>Still withholds the exact date of birth - the age is enough to browse on,
     * and a full birth date is an identifier. A verified account is not a publicly
     * identified one: the platform knows who someone is, the rest of the app knows
     * them by their handle.
     *
     * <p>The display name <em>is</em> published, as of V17. It used to be withheld
     * here; it is now the name shown under the profile picture.
     *
     * <p>Prices live on the items themselves now, so there is none to withhold.
     */
    public static ProfileResponse publicView(Profile profile) {
        return publicView(profile, null);
    }

    public static ProfileResponse publicView(Profile profile, String profilePhotoUrl) {
        return build(profile, profile.getDisplayName(), null, profilePhotoUrl);
    }

    private static ProfileResponse build(Profile profile, String displayName,
                                         LocalDate dateOfBirth, String profilePhotoUrl) {
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
                profile.getUser().getVerificationStatus(),
                profile.getUser().getVerificationStatus() == VerificationStatus.APPROVED,
                profile.getWhatsappNumber(),
                profilePhotoUrl,
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }
}
