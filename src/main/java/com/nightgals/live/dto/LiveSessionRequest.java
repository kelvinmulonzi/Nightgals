package com.nightgals.live.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@Schema(description = "Announce a broadcast")
public record LiveSessionRequest(
        @Schema(example = "Friday warm-up set", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 120) String title,

        @Schema(description = "Where the stream can be played. Supplied by your streaming provider.",
                example = "https://stream.example.com/live/abc123")
        @Size(max = 1000) String playbackUrl,

        @Schema(description = "Leave null to go live immediately") Instant scheduledFor,

        @Schema(description = """
                `FREE` lets anyone watch, including anonymous visitors - useful for pulling
                people in. `EXCLUSIVE` (the default) needs a viewer who has unlocked you.
                """)
        com.nightgals.media.ContentTier tier) {
}
