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
                Always `EXCLUSIVE`, which is also what you get by leaving it out. Every
                broadcast sells entry per show.

                `FREE` is rejected with `free_live_not_allowed` rather than quietly
                charged - a creator who asked for an open room should not find out from
                her earnings that viewers were billed at the door.
                """)
        com.nightgals.media.ContentTier tier,

        @Schema(description = """
                **Required.** What a viewer pays to join this one broadcast, in minor
                units, and yours to set per stream.

                There is no default and no free option: omitting it fails with
                `price_required`, and the platform's floor rejects anything at or near
                zero. Nobody watches a broadcast they have not paid for, so the price is
                decided by you before the room opens rather than after.
                """, example = "5000", requiredMode = Schema.RequiredMode.REQUIRED)
        @Min(value = 0, message = "A price cannot be negative")
        Long accessPriceMinor) {
}
