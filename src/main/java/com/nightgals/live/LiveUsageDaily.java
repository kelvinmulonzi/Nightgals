package com.nightgals.live;

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

import java.time.LocalDate;

/**
 * How many live minutes a creator has used on one day.
 *
 * <p>A counter rather than a SUM over her sessions, because the quota is checked
 * before every broadcast and summing would scan an unbounded history on a busy
 * host.
 *
 * <p>The day is UTC for everybody. One reset time is explainable; twenty-four
 * are not.
 */
@Entity
@Table(name = "live_usage_daily")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiveUsageDaily extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    /**
     * Minutes bought for this day, on top of what the package includes.
     *
     * <p>Kept apart from {@code minutesUsed} because they answer different
     * questions - one is consumption, the other entitlement. Folding paid
     * minutes into the used count would make a creator look busier the more she
     * spent.
     */
    @Column(name = "bonus_minutes", nullable = false)
    @Builder.Default
    private int bonusMinutes = 0;

    @Column(name = "minutes_used", nullable = false)
    @Builder.Default
    private int minutesUsed = 0;
}
