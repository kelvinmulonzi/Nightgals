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
     */
    @Query(value = """
            SELECT p.* FROM profiles p
            JOIN users u ON u.id = p.user_id
            WHERE u.verification_status = 'APPROVED'
              AND u.status = 'ACTIVE'
              AND p.discoverable = TRUE
              AND u.id <> :viewerId
              AND (CAST(:city AS TEXT) IS NULL OR LOWER(p.city) = CAST(:city AS TEXT))
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
            """,
            nativeQuery = true)
    Page<Profile> findFeed(@Param("viewerId") UUID viewerId,
                           @Param("city") String city,
                           Pageable pageable);
}
