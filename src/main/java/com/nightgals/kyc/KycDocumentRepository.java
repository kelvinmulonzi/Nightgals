package com.nightgals.kyc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycDocumentRepository extends JpaRepository<KycDocument, UUID> {

    Optional<KycDocument> findBySubmissionIdAndKind(UUID submissionId, DocumentKind kind);

    List<KycDocument> findBySubmissionId(UUID submissionId);

    /** Decided submissions whose files are past the retention window. */
    @Query("""
            SELECT d FROM KycDocument d
            WHERE d.purgedAt IS NULL
              AND d.submission.reviewedAt IS NOT NULL
              AND d.submission.reviewedAt < :cutoff
            """)
    List<KycDocument> findPurgeable(@Param("cutoff") Instant cutoff);
}
