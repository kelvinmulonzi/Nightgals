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

    /* ── Beyond one broadcast ────────────────────────────────────
       A gift is money leaving one account and arriving in another, and both
       sides need to be able to look it up afterwards. The room's feed is
       ephemeral - it empties when the broadcast ends - so without these the
       only trace a sender has is a line in the credit ledger and the only
       trace a creator has is an earnings entry. */

    /** Everything this account has ever sent, newest first. */
    org.springframework.data.domain.Page<Gift> findBySenderIdOrderByCreatedAtDesc(
            UUID senderId, org.springframework.data.domain.Pageable pageable);

    /** Everything this creator has ever received, newest first. */
    org.springframework.data.domain.Page<Gift> findByCreatorIdOrderByCreatedAtDesc(
            UUID creatorId, org.springframework.data.domain.Pageable pageable);

    /** What this account has spent on gifts, all time. */
    @Query("""
            SELECT COALESCE(SUM(g.amountMinor), 0) FROM Gift g
            WHERE g.sender.id = :userId""")
    long totalSentBy(@Param("userId") UUID userId);

    /**
     * What this creator has been sent, all time, gross.
     *
     * <p>Gross - what viewers paid, before the platform's cut. What she actually
     * keeps is on the earnings ledger, and the two must not be confused: showing
     * a creator a number she cannot withdraw is worse than not showing one.
     */
    @Query("""
            SELECT COALESCE(SUM(g.amountMinor), 0) FROM Gift g
            WHERE g.creator.id = :userId""")
    long totalReceivedBy(@Param("userId") UUID userId);

    long countBySenderId(UUID senderId);

    long countByCreatorId(UUID creatorId);
}
