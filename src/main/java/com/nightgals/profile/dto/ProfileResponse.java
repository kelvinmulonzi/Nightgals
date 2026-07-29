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

        @Schema(description = "Whether this member has passed ID verification")
        VerificationStatus verificationStatus,

        Instant createdAt,
        Instant updatedAt) {

    /** Full view, for the owner or staff. */
    public static ProfileResponse of(Profile profile) {
        return new ProfileResponse(
                profile.getId(),
                profile.getUser().getId(),
                profile.getUser().getUsername(),
                profile.getDisplayName(),
                profile.getBio(),
                profile.getDateOfBirth(),
                profile.getAge(),
                profile.getGender(),
                profile.getCity(),
                profile.getCountry(),
                profile.getVibe(),
                profile.isDiscoverable(),
                profile.getUser().getVerificationStatus(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }

    /**
     * What another member sees.
     *
     * <p>Deliberately withholds both the private nickname and the exact date of
     * birth. A verified account is not a publicly identified one: the platform
     * knows who someone is, the rest of the app knows them by their handle.
     */
    public static ProfileResponse publicView(Profile profile) {
        return new ProfileResponse(
                profile.getId(),
                profile.getUser().getId(),
                profile.getUser().getUsername(),
                null,
                profile.getBio(),
                null,
                profile.getAge(),
                profile.getGender(),
                profile.getCity(),
                profile.getCountry(),
                profile.getVibe(),
                profile.isDiscoverable(),
                profile.getUser().getVerificationStatus(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }
}
