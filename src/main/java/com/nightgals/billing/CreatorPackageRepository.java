package com.nightgals.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorPackageRepository extends JpaRepository<CreatorPackage, UUID> {

    /**
     * The package currently covering this creator.
     *
     * <p>Ordered by expiry so that an upgrade bought before the old one lapsed
     * wins - the creator paid for the better allowance and should have it now,
     * not when the cheaper one runs out.
     */
    @Query("""
            SELECT p FROM CreatorPackage p
            WHERE p.creator.id = :creatorId
              AND p.cancelledAt IS NULL
              AND p.startsAt <= :now
              AND p.expiresAt > :now
            ORDER BY p.expiresAt DESC
            LIMIT 1
            """)
    Optional<CreatorPackage> findActive(@Param("creatorId") UUID creatorId, @Param("now") Instant now);

    List<CreatorPackage> findByCreatorIdOrderByCreatedAtDesc(UUID creatorId);

    /** Drives the admin dashboard's "how many creators are paying" figure. */
    @Query("""
            SELECT COUNT(DISTINCT p.creator.id) FROM CreatorPackage p
            WHERE p.cancelledAt IS NULL AND p.startsAt <= :now AND p.expiresAt > :now
            """)
    long countActiveCreators(@Param("now") Instant now);
}
