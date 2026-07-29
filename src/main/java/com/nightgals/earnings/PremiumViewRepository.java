package com.nightgals.earnings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PremiumViewRepository extends JpaRepository<PremiumView, UUID> {

    boolean existsByViewerIdAndCreatorIdAndPeriod(UUID viewerId, UUID creatorId, String period);

    /** The creators one subscriber viewed in a period - their payment is split among these. */
    @Query("""
            SELECT v.creator.id FROM PremiumView v
            WHERE v.viewer.id = :viewerId AND v.period = :period
            """)
    List<UUID> findCreatorIdsViewedBy(@Param("viewerId") UUID viewerId, @Param("period") String period);

    long countByViewerIdAndPeriod(UUID viewerId, String period);
}
