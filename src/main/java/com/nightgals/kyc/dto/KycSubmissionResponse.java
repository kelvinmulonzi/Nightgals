package com.nightgals.kyc.dto;

import com.nightgals.kyc.DocumentKind;
import com.nightgals.kyc.DocumentType;
import com.nightgals.kyc.KycStatus;
import com.nightgals.kyc.KycSubmission;
import com.nightgals.kyc.RejectionReason;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Schema(description = "A verification attempt as the applicant sees it")
public record KycSubmissionResponse(
        UUID id,
        KycStatus status,
        DocumentType documentType,
        String fullName,
        String countryOfIssue,
        @Schema(description = "Last 4 characters of the document number", example = "5678")
        String documentNumberLast4,
        @Schema(description = "Images uploaded so far") Set<DocumentKind> uploadedDocuments,
        @Schema(description = "Images still needed before this can be submitted")
        Set<DocumentKind> missingDocuments,
        @Schema(description = "True when every required image is present")
        boolean readyToSubmit,
        Instant submittedAt,
        Instant reviewedAt,
        @Schema(description = "Populated only when status is REJECTED") RejectionReason rejectionReason,
        @Schema(description = "What the applicant should do next")
        String guidance,
        Instant createdAt) {

    public static KycSubmissionResponse of(KycSubmission submission) {
        Set<DocumentKind> uploaded = submission.getDocuments().stream()
                .map(com.nightgals.kyc.KycDocument::getKind)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        Set<DocumentKind> missing = submission.getDocumentType().requiredKinds().stream()
                .filter(kind -> !uploaded.contains(kind))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        return new KycSubmissionResponse(
                submission.getId(),
                submission.getStatus(),
                submission.getDocumentType(),
                submission.getFullName(),
                submission.getCountryOfIssue(),
                submission.getDocumentNumberLast4(),
                uploaded,
                missing,
                missing.isEmpty(),
                submission.getSubmittedAt(),
                submission.getReviewedAt(),
                submission.getRejectionReason(),
                guidanceFor(submission, missing),
                submission.getCreatedAt());
    }

    private static String guidanceFor(KycSubmission submission, Set<DocumentKind> missing) {
        return switch (submission.getStatus()) {
            case DRAFT -> missing.isEmpty()
                    ? "All documents uploaded. Call POST /api/v1/me/kyc/submit to send them for review."
                    : "Still needed: " + missing.stream().map(Enum::name).collect(Collectors.joining(", "));
            case PENDING_REVIEW -> "Your documents are with our team. Most reviews finish within 24 hours.";
            case APPROVED -> "You are verified. You can now upload photos and video.";
            case REJECTED -> "Verification was not successful. Start a new submission to try again.";
        };
    }

    /** Note: no field here exposes a storage key or a document image URL. */
    public static List<KycSubmissionResponse> of(List<KycSubmission> submissions) {
        return submissions.stream().map(KycSubmissionResponse::of).toList();
    }
}
