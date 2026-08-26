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
     * The photo this member chose to lead with.
     *
     * <p>Filtered on status too: an item pulled by a moderator must stop being
     * somebody's profile picture the moment it is pulled, not stay on show
     * because it happens to still carry the flag.
     */
    java.util.Optional<MediaAsset> findFirstByUserIdAndPrimaryTrueAndStatus(UUID userId, MediaStatus status);

    List<MediaAsset> findByUserIdAndStatusOrderByPositionAscCreatedAtAsc(UUID userId, MediaStatus status);

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
            """)
    Page<MediaAsset> findVideoFeed(@Param("tiers") List<ContentTier> tiers, Pageable pageable);

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
