package com.nightgals.profile.dto;

import com.nightgals.profile.Gender;
import com.nightgals.profile.Vibe;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

@Schema(description = "Create or replace the caller's profile")
public record ProfileRequest(

        @Schema(description = """
                Optional private nickname, visible only to you and to support. Other
                members always see your username instead, so nothing here is published.
                Leave it out to stay fully pseudonymous.
                """, example = "Amina")
        @Size(min = 2, max = 50)
        String displayName,

        @Schema(example = "Afrobeats and rooftop bars. Always the first one dancing.")
        @Size(max = 500)
        String bio,

        @Schema(description = "Must place the user at 18 or over", example = "1998-04-12",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Past
        LocalDate dateOfBirth,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Gender gender,

        @Schema(example = "Nairobi") @Size(max = 100) String city,
        @Schema(example = "Kenya") @Size(max = 100) String country,

        @Schema(description = "Defaults to ANYTHING when omitted")
        Vibe vibe,

        @Schema(description = "Whether other members can find this profile. Defaults to true.")
        Boolean discoverable) {
}
