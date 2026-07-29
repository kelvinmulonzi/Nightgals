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
     * Newest first, with an optional city filter.
     *
     * <p>{@code city} must already be lower-cased by the caller, and the CAST is
     * load-bearing: without it Postgres cannot infer the type of a null bind
     * parameter and rejects the statement.
     */
    @Query("""
            SELECT p FROM Profile p
            JOIN FETCH p.user u
            WHERE u.verificationStatus = com.nightgals.user.VerificationStatus.APPROVED
              AND u.status = com.nightgals.user.UserStatus.ACTIVE
              AND p.discoverable = true
              AND u.id <> :viewerId
              AND (CAST(:city AS String) IS NULL OR LOWER(p.city) = :city)
            ORDER BY p.createdAt DESC
            """)
    Page<Profile> findFeed(@Param("viewerId") UUID viewerId,
                           @Param("city") String city,
                           Pageable pageable);
}
