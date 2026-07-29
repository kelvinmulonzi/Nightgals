package com.nightgals.live;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiveSessionRepository extends JpaRepository<LiveSession, UUID> {

    List<LiveSession> findByHostIdOrderByCreatedAtDesc(UUID hostId);

    Optional<LiveSession> findFirstByHostIdAndStatus(UUID hostId, LiveStatus status);

    @Query("""
            SELECT s FROM LiveSession s
            JOIN FETCH s.host
            WHERE s.status = com.nightgals.live.LiveStatus.LIVE
            ORDER BY s.startedAt DESC
            """)
    Page<LiveSession> findLive(Pageable pageable);

    /** Drives the "live now" dot on feed cards, one query per page. */
    @Query("""
            SELECT s.host.id FROM LiveSession s
            WHERE s.status = com.nightgals.live.LiveStatus.LIVE
              AND s.host.id IN :hostIds
            """)
    List<UUID> findLiveHostIds(@Param("hostIds") List<UUID> hostIds);
}
