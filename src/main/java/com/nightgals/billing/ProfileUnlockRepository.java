package com.nightgals.billing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileUnlockRepository extends JpaRepository<ProfileUnlock, UUID> {

    Optional<ProfileUnlock> findByViewerIdAndTargetId(UUID viewerId, UUID targetId);

    @Query("""
            SELECT COUNT(u) > 0 FROM ProfileUnlock u
            WHERE u.viewer.id = :viewerId
              AND u.target.id = :targetId
              AND (u.expiresAt IS NULL OR u.expiresAt > :now)
            """)
    boolean hasActiveUnlock(@Param("viewerId") UUID viewerId,
                            @Param("targetId") UUID targetId,
                            @Param("now") Instant now);

    /**
     * Which of these members the viewer has already unlocked. One query for a
     * whole page of the feed, rather than one per card.
     */
    @Query("""
            SELECT u.target.id FROM ProfileUnlock u
            WHERE u.viewer.id = :viewerId
              AND u.target.id IN :targetIds
              AND (u.expiresAt IS NULL OR u.expiresAt > :now)
            """)
    List<UUID> findUnlockedTargetIds(@Param("viewerId") UUID viewerId,
                                     @Param("targetIds") List<UUID> targetIds,
                                     @Param("now") Instant now);

    @Query("""
            SELECT u FROM ProfileUnlock u
            JOIN FETCH u.target
            WHERE u.viewer.id = :viewerId
              AND (u.expiresAt IS NULL OR u.expiresAt > :now)
            ORDER BY u.createdAt DESC
            """)
    Page<ProfileUnlock> findActiveForViewer(@Param("viewerId") UUID viewerId,
                                            @Param("now") Instant now,
                                            Pageable pageable);
}
