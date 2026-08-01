package com.nightgals.live.dto;

import com.nightgals.common.Money;
import com.nightgals.live.LiveSession;
import com.nightgals.live.LiveStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A live broadcast, scheduled or running")
public record LiveSessionResponse(
        UUID id,
        UUID hostId,
        String hostUsername,
        String title,
        LiveStatus status,

        @Schema(description = "FREE is open to everyone; EXCLUSIVE is sold per broadcast")
        com.nightgals.media.ContentTier tier,

        @Schema(description = """
                Where to play the stream. Null when the caller has not bought this
                broadcast - the session is still listed so they know it is happening.
                """)
        String playbackUrl,
        @Schema(description = "True when the caller must pay to get the playback URL")
        boolean locked,

        @Schema(description = "What this broadcast costs, in minor units. Null when it is free.",
                example = "5000")
        Long priceMinor,
        @Schema(example = "5000") String priceDisplay,

        @Schema(description = "Everyone appearing in it, the owner first")
        List<String> hosts,

        Instant scheduledFor,
        @Schema(description = "How long it is expected to run", example = "45") Integer durationMinutes,
        @Schema(description = "When it is due to finish, from the start and the duration")
        Instant scheduledUntil,

        Instant startedAt,
        Instant endedAt) {

    public static LiveSessionResponse of(LiveSession session, boolean unlocked,
                                         List<String> hosts, Long priceMinor) {
        boolean open = session.isFree() || unlocked;
        return new LiveSessionResponse(
                session.getId(),
                session.getHost().getId(),
                session.getHost().getUsername(),
                session.getTitle(),
                session.getStatus(),
                session.getTier(),
                // A free broadcast hands out its URL to anybody.
                open ? session.getPlaybackUrl() : null,
                !open,
                priceMinor,
                priceMinor == null ? null : Money.plain(priceMinor, "XAF"),
                hosts,
                session.getScheduledFor(),
                session.getDurationMinutes(),
                endsAt(session),
                session.getStartedAt(),
                session.getEndedAt());
    }

    private static Instant endsAt(LiveSession session) {
        if (session.getScheduledFor() == null || session.getDurationMinutes() == null) {
            return null;
        }
        return session.getScheduledFor().plusSeconds(session.getDurationMinutes() * 60L);
    }
}
