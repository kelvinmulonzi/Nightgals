package com.nightgals.referral;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface CreditRepository extends JpaRepository<CreditEntry, UUID> {

    /**
     * The balance, summed rather than stored.
     *
     * <p>COALESCE because a user with no entries sums to null, and a null
     * balance would propagate into every price calculation downstream.
     */
    @Query("SELECT COALESCE(SUM(c.amountMinor), 0) FROM CreditEntry c WHERE c.user.id = :userId")
    long balanceOf(@Param("userId") UUID userId);

    Page<CreditEntry> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** Guards the once-per-referred-account rule before the index has to. */
    boolean existsByReferredUserIdAndReason(UUID referredUserId, CreditReason reason);

    /**
     * Whether a top-up has already been credited.
     *
     * <p>Settlement can arrive twice - once by webhook, once by the
     * reconciliation sweep - and the second must not double the balance.
     */
    boolean existsByPurchaseIdAndReason(UUID purchaseId, CreditReason reason);

    /** How many of a referrer's invitations actually converted. */
    long countByUserIdAndReason(UUID userId, CreditReason reason);
}
