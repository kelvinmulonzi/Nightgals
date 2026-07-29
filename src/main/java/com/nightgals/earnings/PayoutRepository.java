package com.nightgals.earnings;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    Page<Payout> findByCreatorIdOrderByCreatedAtDesc(UUID creatorId, Pageable pageable);

    @Query("""
            SELECT p FROM Payout p
            WHERE p.creator.id = :creatorId
              AND p.status IN (com.nightgals.earnings.PayoutStatus.REQUESTED,
                               com.nightgals.earnings.PayoutStatus.APPROVED)
            """)
    Optional<Payout> findOpenForCreator(@Param("creatorId") UUID creatorId);

    @Query("""
            SELECT p FROM Payout p
            JOIN FETCH p.creator
            WHERE p.status IN :statuses
            ORDER BY p.requestedAt ASC
            """)
    Page<Payout> findQueue(@Param("statuses") List<PayoutStatus> statuses, Pageable pageable);

    long countByStatus(PayoutStatus status);

    @Query("""
            SELECT COALESCE(SUM(p.amountMinor), 0) FROM Payout p
            WHERE p.status = com.nightgals.earnings.PayoutStatus.PAID
            """)
    long sumPaidOut();
}
