package com.nightgals.calls;

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
 * One booked private call.
 *
 * <p>The platform owns who may join, what it costs and when it happens. It does
 * not carry the audio or video: {@code roomUrl} points at whatever provider is
 * plugged in, and is handed out only to the two participants and only once the
 * call is paid for.
 */
@Entity
@Table(name = "video_calls")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoCall extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "viewer_id", nullable = false)
    private User viewer;

    /**
     * Copied from the rate at booking time rather than read through it.
     *
     * <p>Changing a price list must not rewrite what somebody already agreed to
     * pay, and a rate can be withdrawn while a booking against it is still open.
     */
    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "scheduled_for", nullable = false)
    private Instant scheduledFor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CallStatus status = CallStatus.PENDING_PAYMENT;

    @Column(name = "room_url", length = 1000)
    private String roomUrl;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "cancelled_reason", length = 300)
    private String cancelledReason;

    public boolean isPaid() {
        return status != CallStatus.PENDING_PAYMENT;
    }

    /** When it is due to finish, from its start and its booked length. */
    public Instant endsAt() {
        return scheduledFor.plusSeconds(durationMinutes * 60L);
    }

    public boolean involves(java.util.UUID userId) {
        return creator.getId().equals(userId) || viewer.getId().equals(userId);
    }
}
