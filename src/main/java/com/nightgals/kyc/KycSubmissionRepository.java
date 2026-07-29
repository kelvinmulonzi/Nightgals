package com.nightgals.kyc;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface KycSubmissionRepository extends JpaRepository<KycSubmission, UUID> {

    /** The submission that currently defines the user's standing. */
    Optional<KycSubmission> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<KycSubmission> findFirstByUserIdAndStatus(UUID userId, KycStatus status);

    boolean existsByUserIdAndStatus(UUID userId, KycStatus status);

    @Query("""
            SELECT s FROM KycSubmission s
            JOIN FETCH s.user
            WHERE s.status = :status
            ORDER BY s.submittedAt ASC
            """)
    Page<KycSubmission> findQueue(@Param("status") KycStatus status, Pageable pageable);

    @Query("""
            SELECT s FROM KycSubmission s
            JOIN FETCH s.user
            ORDER BY s.createdAt DESC
            """)
    Page<KycSubmission> findAllWithUser(Pageable pageable);

    /**
     * Has this exact document already verified somebody else? Used to stop one
     * person holding several approved accounts.
     */
    @Query("""
            SELECT COUNT(s) > 0 FROM KycSubmission s
            WHERE s.documentNumberHash = :hash
              AND s.status = com.nightgals.kyc.KycStatus.APPROVED
              AND s.user.id <> :userId
            """)
    boolean isDocumentAlreadyApprovedForAnotherUser(@Param("hash") String hash, @Param("userId") UUID userId);

    long countByStatus(KycStatus status);
}
