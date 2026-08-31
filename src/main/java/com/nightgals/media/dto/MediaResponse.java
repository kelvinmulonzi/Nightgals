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
                buy it to get a URL.
                """)
        String url,
        String caption,
        int position,
        @Schema(description = "The member's main profile photo") boolean primary,
        @Schema(description = "APPROVED is live; REJECTED means a moderator took it down") MediaStatus status,
        @Schema(description = "Why a moderator removed this. Only set when REJECTED.") String rejectionReason,
        long sizeBytes,
        String contentType,

        @Schema(description = "True when the caller has not paid for this item")
        boolean locked,

        @Schema(description = """
                What this one item costs, in minor units. Present on a locked item -
                a blurred placeholder with no price is not something anybody buys.
                Also returned to the owner, so she can see what she is charging.
                """, example = "3000")
        Long priceMinor,
        @Schema(example = "3000") String priceDisplay,
        @Schema(example = "XAF") String currency,

        @Schema(description = """
                People who have actually watched or opened this, all time.

                Counted on the file being served, not on the tile being listed - so a
                locked item scrolled past on a wall is not a view, and the creator's own
                looks are not either.
                """, example = "412")
        long viewCount,

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
                asset.getUnlockPriceMinor(),
                null,
                null,
                asset.getViewCount(),
                asset.getCreatedAt());
    }

    /**
     * Paywalled view: enough to render a blurred placeholder of the right shape,
     * and the price, with no way to reach the file.
     */
    public static MediaResponse locked(MediaAsset asset, long priceMinor,
                                       String priceDisplay, String currency) {
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
                priceMinor,
                priceDisplay,
                currency,
                asset.getViewCount(),
                asset.getCreatedAt());
    }
}
