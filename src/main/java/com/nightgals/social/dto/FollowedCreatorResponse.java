package com.nightgals.social.dto;

import com.nightgals.social.Follow;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A creator the caller follows")
public record FollowedCreatorResponse(
        UUID userId,
        String username,
        @Schema(description = "Whether they get emailed before her broadcasts") boolean remind,
        Instant followedAt) {

    public static FollowedCreatorResponse of(Follow follow) {
        return new FollowedCreatorResponse(
                follow.getCreator().getId(),
                follow.getCreator().getUsername(),
                follow.isRemind(),
                follow.getCreatedAt());
    }
}
