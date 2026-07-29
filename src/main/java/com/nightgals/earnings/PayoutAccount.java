package com.nightgals.earnings;

import com.nightgals.common.BaseEntity;
import com.nightgals.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Where a creator's money goes. */
@Entity
@Table(name = "payout_accounts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutAccount extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutMethod method;

    /** M-Pesa number or bank account number. */
    @Column(nullable = false, length = 60)
    private String destination;

    @Column(name = "account_name", nullable = false, length = 150)
    private String accountName;

    @Column(name = "bank_name", length = 120)
    private String bankName;

    /** Shown back to the creator so they can confirm without exposing the number. */
    public String maskedDestination() {
        if (destination == null || destination.length() <= 4) {
            return "****";
        }
        return "*".repeat(destination.length() - 4) + destination.substring(destination.length() - 4);
    }
}
