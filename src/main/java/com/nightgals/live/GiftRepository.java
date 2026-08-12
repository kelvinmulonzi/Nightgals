package com.nightgals.live;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GiftRepository extends JpaRepository<Gift, UUID> {

    /**
     * Gifts sent since the client last asked.
     *
     * <p>Strictly after, so the row that ended the previous page is not replayed
     * as a fresh one - a duplicate here means the same gift animating twice.
     *
     * <p>Ascending, unlike most listings: this is a feed being caught up with,
     * and gifts should arrive in the order they were sent.
     */
    @Query("""
            SELECT g FROM Gift g
            WHERE g.liveSession.id = :sessionId AND g.createdAt > :since
            ORDER BY g.createdAt ASC""")
    List<Gift> findSince(@Param("sessionId") UUID sessionId, @Param("since") Instant since);

    /** The opening page, for a viewer joining a broadcast already in progress. */
    List<Gift> findTop50ByLiveSessionIdOrderByCreatedAtDesc(UUID sessionId);

    /** The running total shown on the broadcast. */
    @Query("""
            SELECT COALESCE(SUM(g.amountMinor), 0) FROM Gift g
            WHERE g.liveSession.id = :sessionId""")
    long totalForSession(@Param("sessionId") UUID sessionId);
}
