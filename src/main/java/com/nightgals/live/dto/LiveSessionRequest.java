package com.nightgals.live.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@Schema(description = "Schedule a broadcast, or start one now")
public record LiveSessionRequest(
        @Schema(example = "Friday warm-up set", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 120) String title,

        @Schema(description = "Where the stream can be played. Supplied by your streaming provider.",
                example = "https://stream.example.com/live/abc123")
        @Size(max = 1000) String playbackUrl,

        @Schema(description = """
                The date and time it starts. Leave null to go live immediately.

                With a time, the session goes in the calendar, appears on your profile,
                and your followers are reminded shortly beforehand.
                """)
        Instant scheduledFor,

        @Schema(description = """
                How long you expect to run, in minutes. Checked against your package's
                daily live allowance - 15 minutes on Pro, 45 on Diamond, 2 hours on
                Black Diamond.
                """, example = "45")
        @Min(1) @Max(720) Integer durationMinutes,

        @Schema(description = """
                `FREE` (the default) lets anyone watch, including anonymous visitors. That
                is how a broadcast normally earns now - through gifts sent while it runs,
                not a door charge, and nobody gifts a creator they were not allowed to
                watch.

                `EXCLUSIVE` still sells entry per broadcast, for a creator who genuinely
                wants to ticket one show.
                """)
        com.nightgals.media.ContentTier tier,

        @Schema(description = """
                What a viewer pays to join this one broadcast, in minor units. Yours to
                set, per stream. Ignored on a FREE session; null on an exclusive one
                falls back to the platform default.
                """, example = "5000")
        @Min(value = 0, message = "A price cannot be negative")
        Long accessPriceMinor) {
}
