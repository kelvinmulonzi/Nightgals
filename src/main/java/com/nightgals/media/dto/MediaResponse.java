package com.nightgals.media.dto;

import com.nightgals.media.ContentTier;
import com.nightgals.media.MediaAsset;
import com.nightgals.media.MediaStatus;
import com.nightgals.media.MediaType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A photo or video on a member's profile")
public record MediaResponse(
        UUID id,
        UUID userId,
        MediaType type,

        @Schema(description = "FREE is visible to everyone; EXCLUSIVE is behind the paywall")
        ContentTier tier,

        @Schema(description = """
                Fetch the bytes from here. Null when the item is behind the paywall -
                unlock the member to get a URL.
                """)
        String url,
        String caption,
        int position,
        @Schema(description = "The member's main profile photo") boolean primary,
        @Schema(description = "APPROVED is live; REJECTED means a moderator took it down") MediaStatus status,
        @Schema(description = "Why a moderator removed this. Only set when REJECTED.") String rejectionReason,
        long sizeBytes,
        String contentType,

        @Schema(description = "True when the caller has not paid to see this item")
        boolean locked,

        Instant createdAt) {

    /** Full view: the caller may fetch the bytes. */
    public static MediaResponse of(MediaAsset asset) {
        return new MediaResponse(
                asset.getId(),
                asset.getUser().getId(),
                asset.getType(),
                asset.getTier(),
                "/api/v1/media/" + asset.getId() + "/file",
                asset.getCaption(),
                asset.getPosition(),
                asset.isPrimary(),
                asset.getStatus(),
                asset.getRejectionReason(),
                asset.getSizeBytes(),
                asset.getContentType(),
                false,
                asset.getCreatedAt());
    }

    /**
     * Paywalled view: enough to render a blurred placeholder of the right shape
     * and count, with no way to reach the file.
     */
    public static MediaResponse locked(MediaAsset asset) {
        return new MediaResponse(
                asset.getId(),
                asset.getUser().getId(),
                asset.getType(),
                asset.getTier(),
                null,
                null,
                asset.getPosition(),
                asset.isPrimary(),
                asset.getStatus(),
                null,
                asset.getSizeBytes(),
                asset.getContentType(),
                true,
                asset.getCreatedAt());
    }
}
