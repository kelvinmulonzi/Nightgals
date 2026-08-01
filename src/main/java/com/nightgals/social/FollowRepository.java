package com.nightgals.social;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FollowRepository extends JpaRepository<Follow, UUID> {

    Optional<Follow> findByFollowerIdAndCreatorId(UUID followerId, UUID creatorId);

    boolean existsByFollowerIdAndCreatorId(UUID followerId, UUID creatorId);

    long countByCreatorId(UUID creatorId);

    @Query("""
            SELECT f FROM Follow f JOIN FETCH f.creator
            WHERE f.follower.id = :followerId
            ORDER BY f.createdAt DESC
            """)
    Page<Follow> findFollowing(@Param("followerId") UUID followerId, Pageable pageable);

    /** Who to email about this creator's next broadcast. */
    @Query("""
            SELECT f FROM Follow f JOIN FETCH f.follower
            WHERE f.creator.id = :creatorId AND f.remind = true
            """)
    List<Follow> findRemindable(@Param("creatorId") UUID creatorId);

    /** Which of these creators the caller already follows, in one query. */
    @Query("""
            SELECT f.creator.id FROM Follow f
            WHERE f.follower.id = :followerId AND f.creator.id IN :creatorIds
            """)
    List<UUID> findFollowedAmong(@Param("followerId") UUID followerId,
                                 @Param("creatorIds") List<UUID> creatorIds);
}
