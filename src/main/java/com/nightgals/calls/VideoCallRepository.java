package com.nightgals.calls;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface VideoCallRepository extends JpaRepository<VideoCall, UUID> {

    @Query("""
            SELECT c FROM VideoCall c JOIN FETCH c.creator JOIN FETCH c.viewer
            WHERE c.creator.id = :userId OR c.viewer.id = :userId
            ORDER BY c.scheduledFor DESC
            """)
    Page<VideoCall> findForUser(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Bookings that would collide with a proposed slot.
     *
     * <p>Overlap, not equality: a 60-minute call at 20:00 and a 15-minute one at
     * 20:30 are a clash even though they start at different times, and the
     * unique index on start time alone would happily accept both.
     */
    @Query("""
            SELECT c FROM VideoCall c
            WHERE c.creator.id = :creatorId
              AND c.status IN (com.nightgals.calls.CallStatus.PENDING_PAYMENT,
                               com.nightgals.calls.CallStatus.CONFIRMED,
                               com.nightgals.calls.CallStatus.LIVE)
              AND c.scheduledFor < :endsAt
            """)
    List<VideoCall> findPotentialClashes(@Param("creatorId") UUID creatorId,
                                         @Param("endsAt") Instant endsAt);

    /** Confirmed calls due to start, for the reminder sweep. */
    @Query("""
            SELECT c FROM VideoCall c JOIN FETCH c.creator JOIN FETCH c.viewer
            WHERE c.status = com.nightgals.calls.CallStatus.CONFIRMED
              AND c.scheduledFor BETWEEN :from AND :to
            """)
    List<VideoCall> findStartingBetween(@Param("from") Instant from, @Param("to") Instant to);
}
