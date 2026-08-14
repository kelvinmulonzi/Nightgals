package com.nightgals.reels.dto;

import com.nightgals.reels.Reel;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A creator's short promo clip, live until it expires")
public record ReelResponse(

        UUID id,

        @Schema(description = "The creator this reel advertises. Tapping it should open her profile.")
        UUID creatorId,

        @Schema(description = "Her handle, for the label on the clip", example = "AmberSwallow863")
        String creatorUsername,

        @Schema(description = "Where to fetch the video", example = "/api/v1/reels/9e6764e7-.../file")
        String url,

        String contentType,
        long sizeBytes,
        String caption,

        @Schema(description = "When it stops being shown")
        Instant expiresAt,

        @Schema(description = "False once past expiresAt. Only ever true on the public listing.")
        boolean live,

        Instant createdAt) {

    public static ReelResponse of(Reel reel) {
        return new ReelResponse(
                reel.getId(),
                reel.getPostedBy().getId(),
                reel.getPostedBy().getUsername(),
                "/api/v1/reels/" + reel.getId() + "/file",
                reel.getContentType(),
                reel.getSizeBytes(),
                reel.getCaption(),
                reel.getExpiresAt(),
                reel.isLive(),
                reel.getCreatedAt());
    }
}
