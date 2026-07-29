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

    /** Stops a member opening a second payment for something they are already buying. */
    @Query("""
            SELECT p FROM Purchase p
            WHERE p.user.id = :userId
              AND p.type = com.nightgals.billing.PurchaseType.PROFILE_UNLOCK
              AND p.targetUser.id = :targetId
              AND p.status = com.nightgals.billing.PurchaseStatus.PENDING
            """)
    Optional<Purchase> findPendingUnlock(@Param("userId") UUID userId, @Param("targetId") UUID targetId);
}
