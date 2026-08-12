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

/**
 * One gift sent during a broadcast. Rows are never deleted.
 *
 * <p>The receipt for a transfer of money, so what it records is what was true
 * when it was sent: the code, the label and the price are copied here rather
 * than read back through {@link com.nightgals.config.GiftProperties}. Re-pricing
 * the catalogue tomorrow must not restate what somebody paid today, nor what a
 * creator was told she had earned.
 */
@Entity
@Table(name = "gifts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Gift extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    /**
     * Who received it - the host, denormalised from the session.
     *
     * <p>Stored rather than joined because earnings are read per creator and a
     * broadcast's host is not going to change, so this saves every payout query
     * a join it would otherwise repeat.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "live_session_id", nullable = false)
    private LiveSession liveSession;

    /** The catalogue code as it was at the time, e.g. ROSE. */
    @Column(name = "gift_code", nullable = false, length = 30)
    private String giftCode;

    /** The label and emoji as they were, so an old gift still renders. */
    @Column(name = "gift_label", nullable = false, length = 60)
    private String giftLabel;

    @Column(name = "gift_icon", length = 16)
    private String giftIcon;

    /** What the sender was actually charged, in the currency's minor unit. */
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Optional note from the sender, shown beside the gift. */
    @Column(length = 200)
    private String message;
}
