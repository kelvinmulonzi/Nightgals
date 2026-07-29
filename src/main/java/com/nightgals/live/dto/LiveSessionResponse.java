package com.nightgals.live.dto;

import com.nightgals.live.LiveSession;
import com.nightgals.live.LiveStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A live broadcast")
public record LiveSessionResponse(
        UUID id,
        UUID hostId,
        String hostUsername,
        String title,
        LiveStatus status,

        @Schema(description = "FREE is open to everyone; EXCLUSIVE needs a paying viewer")
        com.nightgals.media.ContentTier tier,

        @Schema(description = """
                Where to play the stream. Null when the caller has not unlocked this
                host - the session is still listed so they know it is happening.
                """)
        String playbackUrl,
        @Schema(description = "True when the caller must pay to get the playback URL")
        boolean locked,
        Instant scheduledFor,
        Instant startedAt,
        Instant endedAt) {

    public static LiveSessionResponse of(LiveSession session, boolean unlocked) {
        return new LiveSessionResponse(
                session.getId(),
                session.getHost().getId(),
                session.getHost().getUsername(),
                session.getTitle(),
                session.getStatus(),
                session.getTier(),
                // A free broadcast hands out its URL to anybody.
                (session.isFree() || unlocked) ? session.getPlaybackUrl() : null,
                !(session.isFree() || unlocked),
                session.getScheduledFor(),
                session.getStartedAt(),
                session.getEndedAt());
    }
}
