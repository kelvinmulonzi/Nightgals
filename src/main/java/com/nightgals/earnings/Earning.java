package com.nightgals.earnings;

import com.nightgals.billing.Purchase;
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
 * One line of a creator's ledger. Append-only: entries change status, never
 * amount, so the history of what was earned always reconstructs the balance.
 */
@Entity
@Table(name = "earnings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Earning extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private EarningType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_id")
    private Purchase purchase;

    /** Attribution period for SUBSCRIPTION_SHARE, e.g. {@code 2026-07}. */
    @Column(length = 7)
    private String period;

    @Column(name = "gross_minor", nullable = false)
    private long grossMinor;

    @Column(name = "commission_minor", nullable = false)
    private long commissionMinor;

    @Column(name = "net_minor", nullable = false)
    private long netMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private EarningStatus status = EarningStatus.PENDING;

    /** When the hold period elapses and this becomes payable. */
    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "payout_id")
    private java.util.UUID payoutId;

    @Column(length = 300)
    private String note;
}
