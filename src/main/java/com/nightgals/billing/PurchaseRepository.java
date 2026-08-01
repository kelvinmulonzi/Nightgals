package com.nightgals.billing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PurchaseRepository extends JpaRepository<Purchase, UUID> {

    Page<Purchase> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<Purchase> findByProviderAndProviderReference(String provider, String providerReference);

    @Query("""
            SELECT p FROM Purchase p
            JOIN FETCH p.user
            WHERE p.status = :status
            ORDER BY p.createdAt ASC
            """)
    Page<Purchase> findByStatus(@Param("status") PurchaseStatus status, Pageable pageable);

    long countByStatus(PurchaseStatus status);

    /* Stop a member opening a second payment for something they are already buying. */

    @Query("""
            SELECT p FROM Purchase p
            WHERE p.user.id = :userId AND p.media.id = :mediaId
              AND p.status = com.nightgals.billing.PurchaseStatus.PENDING
            """)
    Optional<Purchase> findPendingForMedia(@Param("userId") UUID userId, @Param("mediaId") UUID mediaId);

    @Query("""
            SELECT p FROM Purchase p
            WHERE p.user.id = :userId AND p.liveSession.id = :sessionId
              AND p.status = com.nightgals.billing.PurchaseStatus.PENDING
            """)
    Optional<Purchase> findPendingForLive(@Param("userId") UUID userId, @Param("sessionId") UUID sessionId);

    @Query("""
            SELECT p FROM Purchase p
            WHERE p.user.id = :userId AND p.call.id = :callId
              AND p.status = com.nightgals.billing.PurchaseStatus.PENDING
            """)
    Optional<Purchase> findPendingForCall(@Param("userId") UUID userId, @Param("callId") UUID callId);

    /**
     * Settled packages this account has bought.
     *
     * <p>Drives the once-only referral bonus: "their first subscription" means
     * exactly that, so a second package earns the referrer nothing.
     */
    @Query("""
            SELECT COUNT(p) FROM Purchase p
            WHERE p.user.id = :userId
              AND p.type = com.nightgals.billing.PurchaseType.CREATOR_PACKAGE
              AND p.status = com.nightgals.billing.PurchaseStatus.COMPLETED
            """)
    long countSettledPackages(@Param("userId") UUID userId);
}
