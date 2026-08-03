package com.nightgals.billing;

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

/** One attempt to pay for something. Rows are never deleted. */
@Entity
@Table(name = "purchases")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Purchase extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseType type;

    /** Set for PROFILE_UNLOCK only. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id")
    private User targetUser;

    /** Set for SUBSCRIPTION only. */
    @Column(name = "plan_code", length = 30)
    private String planCode;

    /** Set for CREATOR_PACKAGE only: PRO, DIAMOND or BLACK_DIAMOND. */
    @Enumerated(EnumType.STRING)
    @Column(name = "package_code", length = 30)
    private CreatorPackageCode packageCode;

    /** Set for MEDIA_UNLOCK only: the one photo or video being bought. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "media_id")
    private com.nightgals.media.MediaAsset media;

    /** Set for LIVE_ACCESS only: the one broadcast being bought. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "live_session_id")
    private com.nightgals.live.LiveSession liveSession;

    /** Set for CALL_BOOKING only. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "call_id")
    private com.nightgals.calls.VideoCall call;

    /**
     * Credit put towards this purchase.
     *
     * <p>Recorded here as well as in the ledger so a receipt can say "5 000 paid
     * with credit, 10 000 charged" without joining anything.
     */
    @Column(name = "credit_applied_minor", nullable = false)
    @Builder.Default
    private long creditAppliedMinor = 0L;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PurchaseStatus status = PurchaseStatus.PENDING;

    @Column(nullable = false, length = 30)
    private String provider;

    /** The provider's id for this payment; unique per provider. */
    @Column(name = "provider_reference", length = 120)
    private String providerReference;

    /**
     * The number a mobile-money prompt goes to, in international format without
     * a leading plus - 237689686224.
     *
     * <p>On the purchase rather than the account on purpose: someone may pay
     * from a different handset than the one they signed up with, and the number
     * that was actually charged is what a dispute needs to see.
     */
    @Column(name = "payer_msisdn", length = 20)
    private String payerMsisdn;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "completed_at")
    private Instant completedAt;

    public boolean isSettled() {
        return status == PurchaseStatus.COMPLETED;
    }
}
