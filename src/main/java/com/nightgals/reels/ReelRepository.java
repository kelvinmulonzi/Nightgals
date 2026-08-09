package com.nightgals.reels;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReelRepository extends JpaRepository<Reel, UUID> {

    /** Still showing, newest first. */
    List<Reel> findByExpiresAtAfterOrderByCreatedAtDesc(Instant now);

    /**
     * Past their deadline, for the purge.
     *
     * <p>Returns the rows rather than deleting in one statement because each
     * carries a file that has to be removed from storage too - a bulk delete
     * would drop the rows and leak every object they pointed at.
     */
    List<Reel> findByExpiresAtBefore(Instant now);
}
