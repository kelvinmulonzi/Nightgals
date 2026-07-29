package com.nightgals.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    /** The subscription that currently grants access, if any. */
    @Query("""
            SELECT s FROM Subscription s
            WHERE s.user.id = :userId
              AND s.cancelledAt IS NULL
              AND s.startsAt <= :now
              AND s.expiresAt > :now
            ORDER BY s.expiresAt DESC
            LIMIT 1
            """)
    Optional<Subscription> findActive(@Param("userId") UUID userId, @Param("now") Instant now);

    List<Subscription> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
