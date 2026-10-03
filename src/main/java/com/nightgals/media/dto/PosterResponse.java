package com.nightgals.media.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Somebody who has posted, and how much.
 *
 * <p>One row of the moderation listing. It answers "whose work should I look
 * at" before anything is opened: how much there is, how much of it has already
 * been taken down, and how recently they were active.
 */
@Schema(description = "A member with at least one post, and their totals")
public record PosterResponse(
        UUID userId,
        String username,
        String email,
        @Schema(description = "The name on their profile, when they have set one") String displayName,
        String city,
        @Schema(example = "CREATOR") String accountType,
        @Schema(description = "True when the account has been burned") boolean suspended,

        @Schema(description = "Everything they have posted, taken-down items included") long posts,
        long photos,
        long videos,
        @Schema(description = "How many of those a moderator has taken down") long takenDown,
        @Schema(description = "Views across all of their posts") long views,
        Instant lastPostAt,

        @Schema(description = "A photo of theirs to show beside the row; null when they have posted none",
                example = "/api/v1/media/0b7c.../file")
        String thumbnailUrl) {
}
