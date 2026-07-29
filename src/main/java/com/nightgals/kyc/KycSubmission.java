package com.nightgals.kyc;

import com.nightgals.common.BaseEntity;
import com.nightgals.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One identity-verification attempt.
 *
 * <p>Rows are never deleted. A rejected submission stays as the audit trail for
 * why, and the user opens a new one.
 */
@Entity
@Table(name = "kyc_submissions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KycSubmission extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private KycStatus status = KycStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 20)
    private DocumentType documentType;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    /** ISO 3166-1 alpha-2. */
    @Column(name = "country_of_issue", nullable = false, length = 2)
    private String countryOfIssue;

    /** Salted SHA-256 of the document number. The raw number is never stored. */
    @Column(name = "document_number_hash", nullable = false, length = 64)
    private String documentNumberHash;

    @Column(name = "document_number_last4", nullable = false, length = 4)
    private String documentNumberLast4;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", length = 50)
    private RejectionReason rejectionReason;

    @Column(name = "reviewer_notes", length = 1000)
    private String reviewerNotes;

    @OneToMany(mappedBy = "submission", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<KycDocument> documents = new ArrayList<>();

    public Optional<KycDocument> documentOfKind(DocumentKind kind) {
        return documents.stream().filter(d -> d.getKind() == kind).findFirst();
    }

    /** True once every image this document type needs has been uploaded. */
    public boolean hasAllRequiredDocuments() {
        return documents.stream()
                .map(KycDocument::getKind)
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(documentType.requiredKinds());
    }

    public boolean isEditable() {
        return status == KycStatus.DRAFT;
    }
}
