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

    /**
     * Every broadcast is exclusive: entry is sold per show, and nobody watches
     * one without having paid for that one.
     *
     * <p>{@code FREE} is no longer reachable through the API. It survives on this
     * field because broadcasts that ran while it was allowed are still in the
     * table, and rewriting what a finished session charged would be a lie about a
     * night that has already happened. Gifts still land on top of the ticket -
     * the door charge replaced nothing.
     */
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

    /**
     * What a viewer pays to join this one broadcast.
     *
     * <p>Per the brief: "each live stream can also have its own access price".
     * Set on every session created since broadcasts became paid - the API refuses
     * one without it. Null only on rows that predate that, where it falls back to
     * the platform default rather than to free.
     */
    @Column(name = "access_price_minor")
    private Long accessPriceMinor;

    /** When it starts. The date and time halves of the scheduling form. */
    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    /**
     * How long it is expected to run.
     *
     * <p>Shown on the calendar, and checked against the creator's daily live
     * allowance before she is allowed to schedule it at all.
     */
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    /** Set once followers have been told, so a second sweep cannot mail them twice. */
    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

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

    public boolean isScheduled() {
        return status == LiveStatus.SCHEDULED;
    }

    /** Minutes actually broadcast, for metering. Zero until it has ended. */
    public int actualMinutes() {
        if (startedAt == null || endedAt == null) {
            return 0;
        }
        long minutes = java.time.Duration.between(startedAt, endedAt).toMinutes();
        // A session shorter than a minute still consumed a slot; rounding it to
        // zero would make a hundred short streams free.
        return (int) Math.max(1, minutes);
    }
}
