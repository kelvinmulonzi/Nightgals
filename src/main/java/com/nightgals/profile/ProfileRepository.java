package com.nightgals.profile;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {

    Optional<Profile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    /** Several members' profiles in one query, for pages that show many at once. */
    List<Profile> findByUserIdIn(Collection<UUID> userIds);

    /**
     * A profile with the fields that make it one.
     *
     * <p>Distinct from {@link #existsByUserId(UUID)}, which only asks whether a
     * row is present - and a row can now be created by uploading a picture
     * alone, so its presence stopped meaning the profile had been filled in.
     * Reading it as "complete" told a creator who set a photo first that
     * onboarding was finished, while every publishing guard still refused her.
     */
    @Query("""
            SELECT COUNT(p) > 0 FROM Profile p
            WHERE p.user.id = :userId
              AND p.dateOfBirth IS NOT NULL
              AND p.gender IS NOT NULL
            """)
    boolean isCompleteForUser(@Param("userId") UUID userId);

    /**
     * The browse feed: approved, discoverable creators other than the caller.
     *
     * <p>Creators explicitly, not everyone with a profile row. The feed used to
     * lean on the fact that only creators had profiles, which stopped being true
     * once viewers were given one to hang a picture on - and an unstated
     * assumption that quietly turns into "every viewer is now browsable" is worth
     * one line of SQL to close.
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
     * <p>{@code city} must already be lower-cased by the caller. {@code q} is a
     * loose match across handle, display name, city and bio - somebody typing a
     * place into the search box should find that place, not nothing.
     *
     * <p>{@code verifiedOnly} is <em>not</em> the no-op it looks like next to the
     * APPROVED on the first line. Those are two different facts: APPROVED is the
     * publishing gate, and while identity checks are switched off it is granted
     * automatically the moment a profile is saved. The badge is
     * {@code identity_verified_at} - a document a human actually looked at - and
     * that is what this filters on, which is also what the badge on the card
     * means. With KYC off they diverge completely: everybody is APPROVED and
     * almost nobody is verified.
     *
     * <p>{@code paidOnly} is the paid-visibility rule: with it on, a creator
     * appears only while she is on a trial or holds a current package. It is a
     * parameter rather than a constant because the rule follows the
     * creator-packages switch, and SQL cannot read a Spring property - with
     * packages off, everybody is visible and this must not quietly empty the site.
     *
     * <p>{@code tier} replaced a {@code premiumOnly} flag that meant "holds any
     * package at all". There is no such thing as premium here; there are three
     * named tiers, and a browser looking for Black Diamond was being handed
     * everyone with a Pro subscription as well.
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
              AND u.account_type = 'CREATOR'
              AND p.discoverable = TRUE
              AND (CAST(:paidOnly AS BOOLEAN) = FALSE
                   OR u.trial_ends_at > NOW()
                   OR EXISTS (SELECT 1 FROM creator_packages cp
                              WHERE cp.creator_id = u.id
                                AND cp.cancelled_at IS NULL
                                AND cp.starts_at <= NOW()
                                AND cp.expires_at > NOW()))
              AND u.id <> :viewerId
              AND (CAST(:city AS TEXT) IS NULL OR LOWER(p.city) = CAST(:city AS TEXT))
              AND (CAST(:gender AS TEXT) IS NULL OR p.gender = CAST(:gender AS TEXT))
              AND (CAST(:q AS TEXT) IS NULL
                   OR p.display_name ILIKE '%' || CAST(:q AS TEXT) || '%'
                   OR u.username      ILIKE '%' || CAST(:q AS TEXT) || '%'
                   OR p.city          ILIKE '%' || CAST(:q AS TEXT) || '%'
                   OR p.bio           ILIKE '%' || CAST(:q AS TEXT) || '%')
              AND (CAST(:minAge AS INT) IS NULL
                   OR p.date_of_birth <= CURRENT_DATE - MAKE_INTERVAL(years => CAST(:minAge AS INT)))
              AND (CAST(:maxAge AS INT) IS NULL
                   OR p.date_of_birth > CURRENT_DATE - MAKE_INTERVAL(years => CAST(:maxAge AS INT) + 1))
              AND (CAST(:liveOnly AS BOOLEAN) IS NULL OR CAST(:liveOnly AS BOOLEAN) = FALSE
                   OR EXISTS (SELECT 1 FROM live_sessions ls
                              WHERE ls.host_id = u.id AND ls.status = 'LIVE'))
              AND (CAST(:tier AS TEXT) IS NULL
                   OR EXISTS (SELECT 1 FROM creator_packages cp
                              WHERE cp.creator_id = u.id
                                AND cp.package_code = CAST(:tier AS TEXT)
                                AND cp.cancelled_at IS NULL
                                AND cp.starts_at <= NOW()
                                AND cp.expires_at > NOW()))
              AND (CAST(:verifiedOnly AS BOOLEAN) IS NULL
                   OR CAST(:verifiedOnly AS BOOLEAN) = FALSE
                   OR u.identity_verified_at IS NOT NULL)
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
              AND u.account_type = 'CREATOR'
              AND p.discoverable = TRUE
              AND (CAST(:paidOnly AS BOOLEAN) = FALSE
                   OR u.trial_ends_at > NOW()
                   OR EXISTS (SELECT 1 FROM creator_packages cp
                              WHERE cp.creator_id = u.id
                                AND cp.cancelled_at IS NULL
                                AND cp.starts_at <= NOW()
                                AND cp.expires_at > NOW()))
              AND u.id <> :viewerId
              AND (CAST(:city AS TEXT) IS NULL OR LOWER(p.city) = CAST(:city AS TEXT))
              AND (CAST(:gender AS TEXT) IS NULL OR p.gender = CAST(:gender AS TEXT))
              AND (CAST(:q AS TEXT) IS NULL
                   OR p.display_name ILIKE '%' || CAST(:q AS TEXT) || '%'
                   OR u.username      ILIKE '%' || CAST(:q AS TEXT) || '%'
                   OR p.city          ILIKE '%' || CAST(:q AS TEXT) || '%'
                   OR p.bio           ILIKE '%' || CAST(:q AS TEXT) || '%')
              AND (CAST(:minAge AS INT) IS NULL
                   OR p.date_of_birth <= CURRENT_DATE - MAKE_INTERVAL(years => CAST(:minAge AS INT)))
              AND (CAST(:maxAge AS INT) IS NULL
                   OR p.date_of_birth > CURRENT_DATE - MAKE_INTERVAL(years => CAST(:maxAge AS INT) + 1))
              AND (CAST(:liveOnly AS BOOLEAN) IS NULL OR CAST(:liveOnly AS BOOLEAN) = FALSE
                   OR EXISTS (SELECT 1 FROM live_sessions ls
                              WHERE ls.host_id = u.id AND ls.status = 'LIVE'))
              AND (CAST(:tier AS TEXT) IS NULL
                   OR EXISTS (SELECT 1 FROM creator_packages cp
                              WHERE cp.creator_id = u.id
                                AND cp.package_code = CAST(:tier AS TEXT)
                                AND cp.cancelled_at IS NULL
                                AND cp.starts_at <= NOW()
                                AND cp.expires_at > NOW()))
              AND (CAST(:verifiedOnly AS BOOLEAN) IS NULL
                   OR CAST(:verifiedOnly AS BOOLEAN) = FALSE
                   OR u.identity_verified_at IS NOT NULL)
            """,
            nativeQuery = true)
    Page<Profile> findFeed(@Param("viewerId") UUID viewerId,
                           @Param("q") String q,
                           @Param("city") String city,
                           @Param("gender") String gender,
                           @Param("minAge") Integer minAge,
                           @Param("maxAge") Integer maxAge,
                           @Param("liveOnly") Boolean liveOnly,
                           @Param("tier") String tier,
                           @Param("verifiedOnly") Boolean verifiedOnly,
                           @Param("paidOnly") boolean paidOnly,
                           Pageable pageable);

    /**
     * Which cities actually have somebody in them, commonest first.
     *
     * <p>Feeds the shortcut list beside the filters. Counted over exactly the
     * same population the feed draws from - approved, active, discoverable -
     * because a shortcut that promises 12 and delivers 3 is worse than no
     * shortcut. The caller's own card is not excluded here: these are city
     * totals, not search results, and a count that shifted depending on who was
     * looking would be odd.
     *
     * <p>Blank cities are dropped rather than grouped into an "unknown" bucket:
     * city is optional on a profile, and "(none) 41" is not a place anyone wants
     * to browse.
     */
    @Query(value = """
            SELECT INITCAP(TRIM(p.city)) AS city, COUNT(*) AS total
            FROM profiles p
            JOIN users u ON u.id = p.user_id
            WHERE u.verification_status = 'APPROVED'
              AND u.status = 'ACTIVE'
              AND u.account_type = 'CREATOR'
              AND p.discoverable = TRUE
              AND (CAST(:paidOnly AS BOOLEAN) = FALSE
                   OR u.trial_ends_at > NOW()
                   OR EXISTS (SELECT 1 FROM creator_packages cp
                              WHERE cp.creator_id = u.id
                                AND cp.cancelled_at IS NULL
                                AND cp.starts_at <= NOW()
                                AND cp.expires_at > NOW()))
              AND p.city IS NOT NULL
              AND TRIM(p.city) <> ''
            GROUP BY INITCAP(TRIM(p.city))
            ORDER BY total DESC, city ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findPopularCities(@Param("limit") int limit,
                                     @Param("paidOnly") boolean paidOnly);
}
