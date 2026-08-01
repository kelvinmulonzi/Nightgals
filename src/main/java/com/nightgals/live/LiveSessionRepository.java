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

    /** The calendar: scheduled broadcasts still to come, soonest first. */
    @Query("""
            SELECT s FROM LiveSession s
            JOIN FETCH s.host
            WHERE s.status = com.nightgals.live.LiveStatus.SCHEDULED
              AND s.scheduledFor > :now
            ORDER BY s.scheduledFor ASC
            """)
    Page<LiveSession> findUpcoming(@Param("now") java.time.Instant now, Pageable pageable);

    /**
     * Scheduled broadcasts due to start soon whose followers have not been told.
     *
     * <p>Drives the reminder sweep. Bounded on both sides so a session scheduled
     * months out is not mailed about today.
     */
    @Query("""
            SELECT s FROM LiveSession s
            JOIN FETCH s.host
            WHERE s.status = com.nightgals.live.LiveStatus.SCHEDULED
              AND s.reminderSentAt IS NULL
              AND s.scheduledFor BETWEEN :from AND :to
            """)
    List<LiveSession> findNeedingReminder(@Param("from") java.time.Instant from,
                                          @Param("to") java.time.Instant to);

    /** Drives the "live now" dot on feed cards, one query per page. */
    @Query("""
            SELECT s.host.id FROM LiveSession s
            WHERE s.status = com.nightgals.live.LiveStatus.LIVE
              AND s.host.id IN :hostIds
            """)
    List<UUID> findLiveHostIds(@Param("hostIds") List<UUID> hostIds);
}
