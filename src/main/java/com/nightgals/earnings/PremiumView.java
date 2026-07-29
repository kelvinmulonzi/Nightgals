package com.nightgals.earnings;

import com.nightgals.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A subscriber looked at this creator's paid content during this period.
 *
 * <p>One row per (viewer, creator, period): watching somebody fifty times does
 * not earn them fifty shares. This is what makes subscription revenue divisible
 * per subscriber rather than thrown into one pool.
 */
@Entity
@Table(name = "premium_views")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PremiumView {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "viewer_id", nullable = false)
    private User viewer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    /** {@code yyyy-MM}. */
    @Column(nullable = false, length = 7)
    private String period;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
