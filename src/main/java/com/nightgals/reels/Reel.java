package com.nightgals.reels;

import com.nightgals.common.BaseEntity;
import com.nightgals.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A short clip on the public site that deletes itself after a day.
 *
 * <p>Deliberately not a {@code MediaAsset}: that belongs to a creator, sits
 * behind a paywall, counts against her package allowance and stays until she
 * removes it. A reel is the platform's own, free to everyone including
 * signed-out visitors, and temporary.
 */
@Entity
@Table(name = "reels")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Reel extends BaseEntity {

    /** Audit only. A reel is the platform's, so this is never shown to viewers. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "posted_by", nullable = false)
    private User postedBy;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(length = 300)
    private String caption;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Whether it should still be shown.
     *
     * <p>Checked on read as well as swept by the purge job. The sweep reclaims
     * storage; this is what makes the deadline exact — a reel stops being visible
     * the second it expires, not whenever the job next happens to run.
     */
    public boolean isLive() {
        return expiresAt != null && expiresAt.isAfter(Instant.now());
    }
}
