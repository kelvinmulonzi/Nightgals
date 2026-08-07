package com.nightgals.profile.dto;

import com.nightgals.profile.Gender;
import com.nightgals.profile.Vibe;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

@Schema(description = "Create or replace the caller's profile")
public record ProfileRequest(

        @Schema(description = """
                The name shown under your profile picture, to everyone.

                **This is published.** It was private before, and is not any more, so
                do not put anything here you would not want on your public profile.
                Leave it out to be known only by your username.
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
        Boolean discoverable,

        @Schema(description = """
                Optional WhatsApp number, in international format. **Published** on your
                profile as a contact link - leave it out if you would rather members
                reached you only through the platform.
                """, example = "237689686224")
        @Size(max = 20)
        @Pattern(regexp = "^$|^\\+?[0-9][0-9 ()-]{7,19}$",
                message = "must be a phone number in international format")
        String whatsappNumber) {

    /**
     * The shape before a contact number existed.
     *
     * <p>Kept so that adding an optional field did not mean editing every caller
     * that never had one to pass. Omitting it means "no number", which is the
     * same thing an absent JSON property means.
     */
    public ProfileRequest(String displayName, String bio, LocalDate dateOfBirth, Gender gender,
                          String city, String country, Vibe vibe, Boolean discoverable) {
        this(displayName, bio, dateOfBirth, gender, city, country, vibe, discoverable, null);
    }
}
