package com.nightgals.referral;

import com.nightgals.billing.Purchase;
import com.nightgals.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One movement of account credit.
 *
 * <p>Append-only, for the same reason the earnings ledger is: a balance that is
 * stored and incremented is a balance that drifts and cannot be explained. A
 * user's balance is always the sum of their rows.
 *
 * <p>Positive is credit in, negative is credit out.
 */
@Entity
@Table(name = "credit_entries")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CreditReason reason;

    /**
     * For REFERRAL_BONUS: whose first purchase earned it.
     *
     * <p>Also the uniqueness key that makes the bonus once per referred account
     * rather than once per purchase.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referred_user_id")
    private User referredUser;

    /** For SPEND: what the credit went towards. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_id")
    private Purchase purchase;

    @Column(length = 300)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
