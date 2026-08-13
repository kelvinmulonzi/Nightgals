package com.nightgals.profile;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {

    Optional<Profile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    /**
     * The browse feed: verified, discoverable members other than the caller.
     *
     * <p>Ordered by <b>package rank first</b> - Black Diamond above Diamond above
     * Pro above everyone else - and only then by recency. That is what "highest
     * priority in search results and homepage listings" buys.
     *
     * <p>Native because the rank is a scalar subquery in ORDER BY, which JPQL
     * cannot express portably. The subquery takes MAX so a creator holding two
     * overlapping packages ranks by the better one and still yields one row -
     * a join would duplicate her card.
     *
     * <p>{@code city} must already be lower-cased by the caller.
     *
     * <p>Age is filtered as a date range rather than by computing each row's age:
     * {@code minAge} becomes "born on or before today minus that many years", and
     * {@code maxAge} is exclusive at the far end so that asking for 25-30 includes
     * everyone up to the day before their 31st birthday. Comparing the stored date
     * against a bound also lets an index on date_of_birth do the work, which
     * {@code date_part(... age(...))} would not.
     */
    @Query(value = """
            SELECT p.* FROM profiles p
            JOIN users u ON u.id = p.user_id
            WHERE u.verification_status = 'APPROVED'
              AND u.status = 'ACTIVE'
              AND p.discoverable = TRUE
              AND u.id <> :viewerId
              AND (CAST(:city AS TEXT) IS NULL OR LOWER(p.city) = CAST(:city AS TEXT))
              AND (CAST(:minAge AS INT) IS NULL
                   OR p.date_of_birth <= CURRENT_DATE - MAKE_INTERVAL(years => CAST(:minAge AS INT)))
              AND (CAST(:maxAge AS INT) IS NULL
                   OR p.date_of_birth > CURRENT_DATE - MAKE_INTERVAL(years => CAST(:maxAge AS INT) + 1))
              AND (CAST(:liveOnly AS BOOLEAN) IS NULL OR CAST(:liveOnly AS BOOLEAN) = FALSE
                   OR EXISTS (SELECT 1 FROM live_sessions ls
                              WHERE ls.host_id = u.id AND ls.status = 'LIVE'))
            ORDER BY COALESCE((
                SELECT MAX(CASE cp.package_code
                               WHEN 'BLACK_DIAMOND' THEN 3
                               WHEN 'DIAMOND'       THEN 2
                               WHEN 'PRO'           THEN 1
                               ELSE 0 END)
                FROM creator_packages cp
                WHERE cp.creator_id = u.id
                  AND cp.cancelled_at IS NULL
                  AND cp.starts_at <= NOW()
                  AND cp.expires_at > NOW()
            ), 0) DESC, p.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM profiles p
            JOIN users u ON u.id = p.user_id
            WHERE u.verification_status = 'APPROVED'
              AND u.status = 'ACTIVE'
              AND p.discoverable = TRUE
              AND u.id <> :viewerId
              AND (CAST(:city AS TEXT) IS NULL OR LOWER(p.city) = CAST(:city AS TEXT))
              AND (CAST(:minAge AS INT) IS NULL
                   OR p.date_of_birth <= CURRENT_DATE - MAKE_INTERVAL(years => CAST(:minAge AS INT)))
              AND (CAST(:maxAge AS INT) IS NULL
                   OR p.date_of_birth > CURRENT_DATE - MAKE_INTERVAL(years => CAST(:maxAge AS INT) + 1))
              AND (CAST(:liveOnly AS BOOLEAN) IS NULL OR CAST(:liveOnly AS BOOLEAN) = FALSE
                   OR EXISTS (SELECT 1 FROM live_sessions ls
                              WHERE ls.host_id = u.id AND ls.status = 'LIVE'))
            """,
            nativeQuery = true)
    Page<Profile> findFeed(@Param("viewerId") UUID viewerId,
                           @Param("city") String city,
                           @Param("minAge") Integer minAge,
                           @Param("maxAge") Integer maxAge,
                           @Param("liveOnly") Boolean liveOnly,
                           Pageable pageable);
}
