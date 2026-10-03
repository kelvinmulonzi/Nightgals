package com.nightgals.media;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MediaRepository extends JpaRepository<MediaAsset, UUID> {

    List<MediaAsset> findByUserIdOrderByPositionAscCreatedAtAsc(UUID userId);

    /**
     * Everybody who has posted anything, with their totals, most recent first.
     *
     * <p>The moderation listing's first screen. It starts from media rather than
     * from accounts, so a member who has never posted does not appear - most
     * accounts have nothing to moderate, and listing them all buried the ones
     * that do.
     *
     * <p>Taken-down items count towards {@code posts}: this is the staff view,
     * and what was removed is part of what somebody has posted. The thumbnail
     * skips them, though, and skips videos - it is drawn as an image.
     *
     * <p>{@code q} is passed as an empty string rather than null when there is
     * no search, because Postgres cannot infer the type of a bare null parameter.
     */
    @Query(value = """
            SELECT CAST(u.id AS varchar)                                  AS "userId",
                   u.username                                             AS "username",
                   u.email                                                AS "email",
                   p.display_name                                         AS "displayName",
                   p.city                                                 AS "city",
                   u.account_type                                         AS "accountType",
                   (u.status = 'SUSPENDED')                               AS "suspended",
                   COUNT(*)                                               AS "posts",
                   COUNT(*) FILTER (WHERE m.type = 'PHOTO')               AS "photos",
                   COUNT(*) FILTER (WHERE m.type = 'VIDEO')               AS "videos",
                   COUNT(*) FILTER (WHERE m.status = 'REJECTED')          AS "takenDown",
                   COALESCE(SUM(m.view_count), 0)                         AS "views",
                   CAST(EXTRACT(EPOCH FROM MAX(m.created_at)) AS bigint)  AS "lastPostEpoch",
                   CAST((ARRAY_AGG(m.id ORDER BY m.is_primary DESC, m.created_at DESC)
                         FILTER (WHERE m.type = 'PHOTO' AND m.status <> 'REJECTED'))[1] AS varchar)
                                                                          AS "thumbnailId"
            FROM media_assets m
            JOIN users u ON u.id = m.user_id
            LEFT JOIN profiles p ON p.user_id = u.id
            WHERE (:q = '' OR u.username ILIKE '%' || :q || '%' OR u.email ILIKE '%' || :q || '%')
            GROUP BY u.id, u.username, u.email, u.account_type, u.status, p.display_name, p.city
            HAVING (:takenDownOnly = FALSE OR COUNT(*) FILTER (WHERE m.status = 'REJECTED') > 0)
            ORDER BY MAX(m.created_at) DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM (
                SELECT u.id
                FROM media_assets m
                JOIN users u ON u.id = m.user_id
                WHERE (:q = '' OR u.username ILIKE '%' || :q || '%' OR u.email ILIKE '%' || :q || '%')
                GROUP BY u.id
                HAVING (:takenDownOnly = FALSE OR COUNT(*) FILTER (WHERE m.status = 'REJECTED') > 0)
            ) posters
            """,
            nativeQuery = true)
    Page<PosterRow> posters(@Param("q") String q, @Param("takenDownOnly") boolean takenDownOnly, Pageable pageable);

    /** One poster. Ids and the timestamp come back as text and epoch seconds, independent of the driver. */
    interface PosterRow {
        String getUserId();

        String getUsername();

        String getEmail();

        String getDisplayName();

        String getCity();

        String getAccountType();

        boolean getSuspended();

        long getPosts();

        long getPhotos();

        long getVideos();

        long getTakenDown();

        long getViews();

        long getLastPostEpoch();

        String getThumbnailId();
    }

    /**
     * The photo this member chose to lead with.
     *
     * <p>Filtered on status too: an item pulled by a moderator must stop being
     * somebody's profile picture the moment it is pulled, not stay on show
     * because it happens to still carry the flag.
     */
    java.util.Optional<MediaAsset> findFirstByUserIdAndPrimaryTrueAndStatus(UUID userId, MediaStatus status);

    List<MediaAsset> findByUserIdAndStatusOrderByPositionAscCreatedAtAsc(UUID userId, MediaStatus status);

    /**
     * The same gallery, one page at a time.
     *
     * <p>A creator with a large gallery made {@link #findByUserIdAndStatusOrderByPositionAscCreatedAtAsc}
     * worth paging rather than returning in one call - see
     * {@link MediaService#listPublic(UUID, com.nightgals.user.User, Pageable)}.
     */
    Page<MediaAsset> findByUserIdAndStatusOrderByPositionAscCreatedAtAsc(
            UUID userId, MediaStatus status, Pageable pageable);

    /** Everything this member has posted, whatever its type or state. */
    long countByUserId(UUID userId);

    long countByUserIdAndType(UUID userId, MediaType type);

    long countByUserIdAndTypeAndTier(UUID userId, MediaType type, ContentTier tier);

    /** Everything recently posted, for staff spot-checks. */
    @Query("""
            SELECT m FROM MediaAsset m
            JOIN FETCH m.user
            ORDER BY m.createdAt DESC
            """)
    Page<MediaAsset> findRecent(Pageable pageable);

    /** Clears the current primary before a new one is set. */
    @Modifying
    @Query("UPDATE MediaAsset m SET m.primary = false WHERE m.user.id = :userId AND m.primary = true")
    int clearPrimary(@Param("userId") UUID userId);

    long countByStatus(MediaStatus status);

    /**
     * Every creator's video wall, newest first.
     *
     * <p>The visibility predicates are deliberately the same set the member feed
     * uses - approved, active, a creator, discoverable - so a clip can never be
     * browsable here while the profile that posted it is hidden from Discover.
     * The profile is tested with EXISTS rather than joined: a join would be a
     * second row source on a query that must count videos, not profiles.
     *
     * <p>{@code tiers} is always a non-empty list. "Everything" passes both
     * values rather than a null the query has to test for - a null enum
     * parameter has no type for Hibernate to bind, and the list says the same
     * thing without the cast.
     */
    @Query(value = """
            SELECT m FROM MediaAsset m
            JOIN FETCH m.user u
            WHERE m.type = com.nightgals.media.MediaType.VIDEO
              AND m.status = com.nightgals.media.MediaStatus.APPROVED
              AND m.tier IN :tiers
              AND u.verificationStatus = com.nightgals.user.VerificationStatus.APPROVED
              AND u.status = com.nightgals.user.UserStatus.ACTIVE
              AND u.accountType = com.nightgals.user.AccountType.CREATOR
              AND EXISTS (SELECT 1 FROM Profile p WHERE p.user = u AND p.discoverable = true)
              AND (:paidOnly = FALSE
                   OR u.trialEndsAt > CURRENT_TIMESTAMP
                   OR EXISTS (SELECT 1 FROM CreatorPackage cp
                              WHERE cp.creator = u
                                AND cp.cancelledAt IS NULL
                                AND cp.startsAt <= CURRENT_TIMESTAMP
                                AND cp.expiresAt > CURRENT_TIMESTAMP))
            ORDER BY m.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(m) FROM MediaAsset m
            WHERE m.type = com.nightgals.media.MediaType.VIDEO
              AND m.status = com.nightgals.media.MediaStatus.APPROVED
              AND m.tier IN :tiers
              AND m.user.verificationStatus = com.nightgals.user.VerificationStatus.APPROVED
              AND m.user.status = com.nightgals.user.UserStatus.ACTIVE
              AND m.user.accountType = com.nightgals.user.AccountType.CREATOR
              AND EXISTS (SELECT 1 FROM Profile p WHERE p.user = m.user AND p.discoverable = true)
              AND (:paidOnly = FALSE
                   OR m.user.trialEndsAt > CURRENT_TIMESTAMP
                   OR EXISTS (SELECT 1 FROM CreatorPackage cp
                              WHERE cp.creator = m.user
                                AND cp.cancelledAt IS NULL
                                AND cp.startsAt <= CURRENT_TIMESTAMP
                                AND cp.expiresAt > CURRENT_TIMESTAMP))
            """)
    Page<MediaAsset> findVideoFeed(@Param("tiers") List<ContentTier> tiers,
                                   @Param("paidOnly") boolean paidOnly,
                                   Pageable pageable);

    /**
     * The lead photo for each of several members at once.
     *
     * <p>For surfaces that need a face beside somebody else's content and
     * nothing else about them - a page of videos from twenty creators would
     * otherwise be twenty separate lookups.
     */
    @Query("""
            SELECT m FROM MediaAsset m
            WHERE m.user.id IN :userIds
              AND m.primary = true
              AND m.status = :status
            """)
    List<MediaAsset> findPrimaryForUsers(@Param("userIds") List<UUID> userIds,
                                         @Param("status") MediaStatus status);

    /** Approved media for a whole page of the feed in one query. */
    @Query("""
            SELECT m FROM MediaAsset m
            JOIN FETCH m.user
            WHERE m.user.id IN :userIds
              AND m.status = :status
            ORDER BY m.position ASC, m.createdAt ASC
            """)
    List<MediaAsset> findApprovedForUsers(@Param("userIds") List<UUID> userIds,
                                          @Param("status") MediaStatus status);
}
