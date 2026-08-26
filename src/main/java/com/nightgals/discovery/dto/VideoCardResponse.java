package com.nightgals.discovery.dto;

import com.nightgals.media.ContentTier;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One clip on the video wall.
 *
 * <p>A member card with a video attached would be the wrong shape here: this
 * page is a wall of clips, and the same creator may appear on it five times.
 * So the creator is carried <em>on</em> the clip - just the handle, the name and
 * a face, which is everything needed to caption a tile and send somebody to the
 * profile behind it.
 *
 * <p>{@code tier} and {@code locked} are not the same question and both are
 * needed. Tier is what the creator decided the clip is; locked is whether this
 * particular caller may watch it. An EXCLUSIVE clip is unlocked for somebody who
 * paid, and a client that inferred one from the other would either paywall what
 * a viewer already owns or promise a file the server will refuse.
 */
@Schema(description = "A video on the public wall, with the creator who posted it")
public record VideoCardResponse(

        UUID id,
        UUID userId,
        String username,

        @Schema(description = "The creator's chosen name. Null if she never set one - fall back to the handle.")
        String displayName,

        @Schema(description = """
                The creator's face, as a fetchable path. Null when she has no picture at
                all - draw a placeholder.
                """)
        String creatorPhotoUrl,

        @Schema(description = """
                True when this creator's identity documents were checked by a human.
                Carried on the clip because the wall never loads her profile - the
                one place a viewer would otherwise have found it out.
                """)
        boolean verified,

        @Schema(description = "FREE is the shop window; EXCLUSIVE is what viewers pay for")
        ContentTier tier,

        @Schema(description = "Where the bytes are. Null when this caller has not paid for it.")
        String url,

        String caption,
        String contentType,
        long sizeBytes,

        @Schema(description = "True when the caller may not watch it yet")
        boolean locked,

        @Schema(description = "What unlocking costs, in minor units. Only on a locked clip.", example = "3000")
        Long priceMinor,
        @Schema(example = "3 000") String priceDisplay,
        @Schema(example = "XAF") String currency,

        Instant createdAt) {
}
