package com.nightgals.reels;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReelRepository extends JpaRepository<Reel, UUID> {

    /**
     * Still showing, newest first — from accounts that are still on the site.
     *
     * <p>The host filter is not incidental. This is the landing page, so a reel
     * outlives the removal of the person who posted it unless the query says
     * otherwise: burning an account took her out of Discover and off the video
     * wall and left her face playing on the front page for another day.
     */
    @Query("""
            SELECT r FROM Reel r
            JOIN FETCH r.postedBy u
            WHERE r.expiresAt > :now
              AND u.status = com.nightgals.user.UserStatus.ACTIVE
              AND (:paidOnly = FALSE
                   OR u.trialEndsAt > CURRENT_TIMESTAMP
                   OR EXISTS (SELECT 1 FROM CreatorPackage cp
                              WHERE cp.creator = u
                                AND cp.cancelledAt IS NULL
                                AND cp.startsAt <= CURRENT_TIMESTAMP
                                AND cp.expiresAt > CURRENT_TIMESTAMP))
            ORDER BY r.createdAt DESC
            """)
    List<Reel> findLive(@Param("now") Instant now, @Param("paidOnly") boolean paidOnly);

    /**
     * Past their deadline, for the purge.
     *
     * <p>Returns the rows rather than deleting in one statement because each
     * carries a file that has to be removed from storage too - a bulk delete
     * would drop the rows and leak every object they pointed at.
     */
    List<Reel> findByExpiresAtBefore(Instant now);

    /** Everything one creator has posted, newest first. */
    List<Reel> findByPostedByIdOrderByCreatedAtDesc(UUID creatorId);

    /** How many of hers are still showing, for the per-creator cap. */
    long countByPostedByIdAndExpiresAtAfter(UUID creatorId, Instant now);
}
