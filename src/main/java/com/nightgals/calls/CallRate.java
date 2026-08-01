package com.nightgals.calls;

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

/**
 * What one creator charges for a private call of a given length.
 *
 * <p>One row per creator per duration. The six lengths in the brief are
 * suggestions, so a creator who only wants to offer 15 and 60 minutes simply
 * has two rows rather than four disabled ones.
 */
@Entity
@Table(name = "call_rates")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallRate extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    /**
     * Withdrawn rates are deactivated, never deleted: a booking that references
     * one still has to be able to explain what was charged.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
