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
 * Somebody appearing in a broadcast.
 *
 * <p>The session still has one owner - {@code LiveSession.host} - because
 * somebody has to own the row, the daily quota it consumes and the money it
 * takes. A co-host is an invitation to appear in it, not joint ownership.
 */
@Entity
@Table(name = "live_hosts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiveHost extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private LiveSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private HostRole role = HostRole.CO_HOST;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private HostStatus status = HostStatus.INVITED;

    @Column(name = "responded_at")
    private Instant respondedAt;

    public boolean isOnAir() {
        return status == HostStatus.ACCEPTED;
    }
}
