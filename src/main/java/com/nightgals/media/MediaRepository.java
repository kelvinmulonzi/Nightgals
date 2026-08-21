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
