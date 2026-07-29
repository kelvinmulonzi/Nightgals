package com.nightgals.kyc;

import com.nightgals.common.ApiException;
import com.nightgals.common.PageResponse;
import com.nightgals.kyc.dto.KycReviewItemResponse;
import com.nightgals.kyc.dto.KycReviewRequest;
import com.nightgals.profile.ProfileRepository;
import com.nightgals.storage.StorageService;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.VerificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The reviewer's side of verification: work the queue, open the images, record
 * a decision.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KycReviewService {

    private final KycSubmissionRepository submissionRepository;
    private final KycDocumentRepository documentRepository;
    private final KycAccessLogRepository accessLogRepository;
    private final ProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;

    @Transactional(readOnly = true)
    public PageResponse<KycReviewItemResponse> queue(Pageable pageable) {
        return PageResponse.from(
                submissionRepository.findQueue(KycStatus.PENDING_REVIEW, pageable),
                this::toReviewItem);
    }

    @Transactional(readOnly = true)
    public PageResponse<KycReviewItemResponse> all(Pageable pageable) {
        return PageResponse.from(submissionRepository.findAllWithUser(pageable), this::toReviewItem);
    }

    @Transactional(readOnly = true)
    public KycReviewItemResponse get(UUID submissionId) {
        return toReviewItem(requireSubmission(submissionId));
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return submissionRepository.countByStatus(KycStatus.PENDING_REVIEW);
    }

    /**
     * Streams an identity document to a reviewer, recording who looked at it.
     * The access log write is part of the same transaction as the read, so an
     * image cannot be served without the log entry succeeding.
     */
    @Transactional
    public DocumentDownload downloadDocument(UUID documentId, User reviewer, String ipAddress) {
        KycDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> ApiException.notFound("Document"));

        if (document.isPurged()) {
            throw ApiException.notFound("Document (purged under the retention policy)");
        }

        accessLogRepository.save(KycAccessLog.builder()
                .document(document)
                .accessedBy(reviewer)
                .ipAddress(ipAddress)
                .build());

        log.info("KYC document {} accessed by {}", documentId, reviewer.getEmail());
        return new DocumentDownload(
                storageService.load(document.getStorageKey()),
                document.getContentType(),
                document.getKind());
    }

    /** Approve or reject, and move the account's verification status with it. */
    @Transactional
    public KycReviewItemResponse review(UUID submissionId, KycReviewRequest request, User reviewer) {
        KycSubmission submission = requireSubmission(submissionId);

        if (submission.getStatus() != KycStatus.PENDING_REVIEW) {
            throw ApiException.conflict("not_pending",
                    "This submission is " + submission.getStatus() + " and cannot be reviewed");
        }
        if (submission.getUser().getId().equals(reviewer.getId())) {
            throw ApiException.forbidden("self_review", "You cannot review your own submission");
        }

        boolean approve = Boolean.TRUE.equals(request.approve());
        if (!approve && request.rejectionReason() == null) {
            throw ApiException.badRequest("reason_required", "A rejection reason is required");
        }
        if (approve && submissionRepository.isDocumentAlreadyApprovedForAnotherUser(
                submission.getDocumentNumberHash(), submission.getUser().getId())) {
            throw ApiException.conflict("duplicate_document",
                    "This document already verifies another account. Reject as DUPLICATE_ACCOUNT.");
        }

        submission.setStatus(approve ? KycStatus.APPROVED : KycStatus.REJECTED);
        submission.setRejectionReason(approve ? null : request.rejectionReason());
        submission.setReviewerNotes(request.reviewerNotes());
        submission.setReviewedAt(Instant.now());
        submission.setReviewedBy(reviewer);

        User applicant = userRepository.findById(submission.getUser().getId()).orElseThrow();
        applicant.setVerificationStatus(approve ? VerificationStatus.APPROVED : VerificationStatus.REJECTED);

        log.info("KYC submission {} {} by {}", submissionId, approve ? "APPROVED" : "REJECTED", reviewer.getEmail());
        return toReviewItem(submission);
    }

    private KycSubmission requireSubmission(UUID submissionId) {
        return submissionRepository.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("Submission"));
    }

    private KycReviewItemResponse toReviewItem(KycSubmission submission) {
        LocalDate profileDob = profileRepository.findByUserId(submission.getUser().getId())
                .map(p -> p.getDateOfBirth())
                .orElse(null);
        boolean duplicate = submissionRepository.isDocumentAlreadyApprovedForAnotherUser(
                submission.getDocumentNumberHash(), submission.getUser().getId());
        return KycReviewItemResponse.of(submission, profileDob, duplicate);
    }

    public record DocumentDownload(Resource resource, String contentType, DocumentKind kind) {
    }
}
