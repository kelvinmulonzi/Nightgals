package com.nightgals.live;

import com.nightgals.common.BaseEntity;
import com.nightgals.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Metadata about a member's live broadcast.
 *
 * <p>This application does not ingest, transcode or serve video. {@code playbackUrl}
 * points at whatever streaming provider the host is using; all Nightgals does is
 * record that the session exists and decide who is allowed to see the URL.
 */
@Entity
@Table(name = "live_sessions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiveSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_id", nullable = false)
    private User host;

    @Column(nullable = false, length = 120)
    private String title;

    /** A free broadcast is open to anyone; an exclusive one needs a paying viewer. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private com.nightgals.media.ContentTier tier = com.nightgals.media.ContentTier.EXCLUSIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private LiveStatus status = LiveStatus.SCHEDULED;

    /** Withheld from viewers who have not paid. */
    @Column(name = "playback_url", length = 1000)
    private String playbackUrl;

    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    public boolean isLive() {
        return status == LiveStatus.LIVE;
    }

    public boolean isFree() {
        return tier == com.nightgals.media.ContentTier.FREE;
    }
}
