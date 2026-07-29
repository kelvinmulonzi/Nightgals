package com.nightgals.kyc.dto;

import com.nightgals.kyc.DocumentKind;
import com.nightgals.kyc.DocumentType;
import com.nightgals.kyc.KycStatus;
import com.nightgals.kyc.KycSubmission;
import com.nightgals.kyc.RejectionReason;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = """
        A verification attempt as a reviewer sees it. Carries the applicant's
        stated identity so it can be compared against the document images, plus
        the ids needed to fetch those images.
        """)
public record KycReviewItemResponse(
        UUID submissionId,
        UUID userId,
        String email,
        KycStatus status,
        DocumentType documentType,
        @Schema(description = "Name as typed by the applicant. Compare with the document.")
        String statedFullName,
        @Schema(description = "Date of birth as typed by the applicant. Compare with the document.")
        LocalDate statedDateOfBirth,
        @Schema(description = "Date of birth from the applicant's profile. Should match the above.")
        LocalDate profileDateOfBirth,
        String countryOfIssue,
        String documentNumberLast4,
        @Schema(description = "True if this document number already verified a different account")
        boolean possibleDuplicate,
        @Schema(description = "Fetch each with GET /api/v1/admin/kyc/documents/{documentId}/file")
        List<DocumentRef> documents,
        Instant submittedAt,
        Instant reviewedAt,
        String reviewedByEmail,
        RejectionReason rejectionReason,
        String reviewerNotes) {

    @Schema(description = "Pointer to one stored image")
    public record DocumentRef(
            UUID documentId,
            DocumentKind kind,
            String contentType,
            long sizeBytes,
            @Schema(description = "True once the file has been purged under the retention policy")
            boolean purged) {
    }

    public static KycReviewItemResponse of(KycSubmission s, LocalDate profileDob, boolean possibleDuplicate) {
        return new KycReviewItemResponse(
                s.getId(),
                s.getUser().getId(),
                s.getUser().getEmail(),
                s.getStatus(),
                s.getDocumentType(),
                s.getFullName(),
                s.getDateOfBirth(),
                profileDob,
                s.getCountryOfIssue(),
                s.getDocumentNumberLast4(),
                possibleDuplicate,
                s.getDocuments().stream()
                        .map(d -> new DocumentRef(d.getId(), d.getKind(), d.getContentType(),
                                d.getSizeBytes(), d.isPurged()))
                        .toList(),
                s.getSubmittedAt(),
                s.getReviewedAt(),
                s.getReviewedBy() == null ? null : s.getReviewedBy().getEmail(),
                s.getRejectionReason(),
                s.getReviewerNotes());
    }
}
