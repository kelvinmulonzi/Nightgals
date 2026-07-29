package com.nightgals.earnings;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EarningRepository extends JpaRepository<Earning, UUID> {

    Page<Earning> findByCreatorIdOrderByCreatedAtDesc(UUID creatorId, Pageable pageable);

    @Query("""
            SELECT COALESCE(SUM(e.netMinor), 0) FROM Earning e
            WHERE e.creator.id = :creatorId AND e.status = :status
            """)
    long sumNetByStatus(@Param("creatorId") UUID creatorId, @Param("status") EarningStatus status);

    @Query("""
            SELECT COALESCE(SUM(e.netMinor), 0) FROM Earning e
            WHERE e.creator.id = :creatorId
              AND e.status IN (com.nightgals.earnings.EarningStatus.PENDING,
                               com.nightgals.earnings.EarningStatus.AVAILABLE,
                               com.nightgals.earnings.EarningStatus.RESERVED,
                               com.nightgals.earnings.EarningStatus.PAID)
            """)
    long sumLifetimeNet(@Param("creatorId") UUID creatorId);

    List<Earning> findByCreatorIdAndStatus(UUID creatorId, EarningStatus status);

    List<Earning> findByPayoutId(UUID payoutId);

    /** Entries whose hold period has elapsed. */
    @Query("""
            SELECT e FROM Earning e
            WHERE e.status = com.nightgals.earnings.EarningStatus.PENDING
              AND e.availableAt <= :now
            """)
    List<Earning> findReleasable(@Param("now") Instant now);

    @Query("SELECT COUNT(e) > 0 FROM Earning e WHERE e.purchase.id = :purchaseId AND e.type = :type")
    boolean existsForPurchase(@Param("purchaseId") UUID purchaseId, @Param("type") EarningType type);

    /** Has this subscription already paid this creator for this period? */
    @Query("""
            SELECT COUNT(e) > 0 FROM Earning e
            WHERE e.purchase.id = :purchaseId
              AND e.creator.id = :creatorId
              AND e.period = :period
              AND e.type = com.nightgals.earnings.EarningType.SUBSCRIPTION_SHARE
            """)
    boolean existsSubscriptionShare(@Param("purchaseId") UUID purchaseId,
                                    @Param("creatorId") UUID creatorId,
                                    @Param("period") String period);

    @Query("""
            SELECT COALESCE(SUM(e.commissionMinor), 0) FROM Earning e
            WHERE e.status <> com.nightgals.earnings.EarningStatus.REVERSED
            """)
    long sumPlatformCommission();

    @Query("""
            SELECT COALESCE(SUM(e.netMinor), 0) FROM Earning e
            WHERE e.status = :status
            """)
    long sumNetAcrossPlatform(@Param("status") EarningStatus status);
}
