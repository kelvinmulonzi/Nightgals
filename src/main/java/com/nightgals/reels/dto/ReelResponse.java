package com.nightgals.reels.dto;

import com.nightgals.reels.Reel;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A short clip on the public site, live until it expires")
public record ReelResponse(

        UUID id,

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
                "/api/v1/reels/" + reel.getId() + "/file",
                reel.getContentType(),
                reel.getSizeBytes(),
                reel.getCaption(),
                reel.getExpiresAt(),
                reel.isLive(),
                reel.getCreatedAt());
    }
}
