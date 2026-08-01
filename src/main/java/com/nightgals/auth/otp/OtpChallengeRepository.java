package com.nightgals.auth.otp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, UUID> {

    @Query("""
            SELECT c FROM OtpChallenge c
            JOIN FETCH c.user
            WHERE c.id = :id
            """)
    Optional<OtpChallenge> findWithUser(@Param("id") UUID id);

    /** Feeds the per-account rate limit. */
    @Query("""
            SELECT COUNT(c) FROM OtpChallenge c
            WHERE c.user.id = :userId AND c.createdAt > :since
            """)
    long countRecent(@Param("userId") UUID userId, @Param("since") Instant since);

    /**
     * Challenges of this kind that have not been spent yet.
     *
     * <p>Loaded as entities rather than retired by a bulk UPDATE on purpose: a
     * bulk update writes behind the persistence context's back, so anything
     * already loaded in the same transaction keeps its stale {@code consumedAt}
     * and a supposedly-retired code still verifies.
     */
    @Query("""
            SELECT c FROM OtpChallenge c
            WHERE c.user.id = :userId AND c.purpose = :purpose AND c.consumedAt IS NULL
            """)
    List<OtpChallenge> findOutstanding(@Param("userId") UUID userId,
                                       @Param("purpose") OtpPurpose purpose);

    /** Housekeeping: spent or long-dead rows carry no value. */
    @Modifying
    @Query("DELETE FROM OtpChallenge c WHERE c.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
