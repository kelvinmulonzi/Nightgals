package com.nightgals.billing;

import com.nightgals.common.BaseEntity;
import com.nightgals.user.User;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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

    /** Set for PROFILE_UNLOCK only: the creator whose gallery is being bought. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id")
    private User targetUser;

    /**
     * Set for PROFILE_UNLOCK only: exactly which items this payment covers.
     *
     * <p>A list, not a standing claim on the profile. It is fixed when the
     * purchase is created, so the buyer receives what they were charged for even
     * if the creator posts or deletes something before the payment settles - and
     * it is what makes "anything posted later is bought separately" a fact about
     * the data rather than a rule somebody has to remember to apply.
     *
     * <p>Ids rather than a relationship: this is a receipt, and it has to stay
     * readable after a creator deletes one of the items on it.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "purchase_media",
            joinColumns = @JoinColumn(name = "purchase_id"))
    @Column(name = "media_id", nullable = false)
    @Builder.Default
    private java.util.Set<java.util.UUID> bundleMediaIds = new java.util.HashSet<>();

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

    /**
     * The provider's id for this payment; unique per provider.
     *
     * <p>What it holds shifts as the payment progresses, and differs by provider:
     * MTN uses the purchase id throughout and replaces it with the financial
     * transaction id on success, while Stripe holds the Checkout Session id
     * ({@code cs_...}) while pending and the payment intent ({@code pi_...}) once
     * paid - the latter being what a refund or a dispute is filed against.
     */
    @Column(name = "provider_reference", length = 255)
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

    /**
     * How many extra live minutes this bought, for LIVE_EXTENSION. Null otherwise.
     */
    @Column(name = "extension_minutes")
    private Integer extensionMinutes;

    /**
     * The day those minutes are for.
     *
     * <p>Stored rather than derived from the settlement time: a purchase started
     * at 23:59 and approved at 00:01 belongs to the broadcast it was bought for,
     * not to the following day.
     */
    @Column(name = "extension_date")
    private java.time.LocalDate extensionDate;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "completed_at")
    private Instant completedAt;

    public boolean isSettled() {
        return status == PurchaseStatus.COMPLETED;
    }
}
