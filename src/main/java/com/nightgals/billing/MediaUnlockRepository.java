package com.nightgals.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaUnlockRepository extends JpaRepository<MediaUnlock, UUID> {

    Optional<MediaUnlock> findByViewerIdAndMediaId(UUID viewerId, UUID mediaId);

    boolean existsByViewerIdAndMediaId(UUID viewerId, UUID mediaId);

    /**
     * Which of these items the viewer already owns.
     *
     * <p>One query for a whole gallery rather than one per tile - a profile with
     * forty photos would otherwise be forty round trips before anything renders.
     */
    @Query("""
            SELECT u.media.id FROM MediaUnlock u
            WHERE u.viewer.id = :viewerId AND u.media.id IN :mediaIds
            """)
    List<UUID> findUnlockedAmong(@Param("viewerId") UUID viewerId,
                                 @Param("mediaIds") List<UUID> mediaIds);

    long countByViewerId(UUID viewerId);
}
