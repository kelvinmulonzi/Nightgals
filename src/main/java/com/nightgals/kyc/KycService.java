package com.nightgals.kyc;

import com.nightgals.common.ApiException;
import com.nightgals.common.Hashing;
import com.nightgals.config.AppProperties;
import com.nightgals.kyc.dto.KycSubmissionRequest;
import com.nightgals.kyc.dto.KycSubmissionResponse;
import com.nightgals.profile.Profile;
import com.nightgals.profile.ProfileRepository;
import com.nightgals.storage.StorageService;
import com.nightgals.storage.StoredFile;
import com.nightgals.storage.UploadValidator;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.VerificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The applicant's side of identity verification: open a submission, attach the
 * required images, send it for review.
 *
 * <p>The reviewer's side lives in {@link KycReviewService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KycService {

    private final KycSubmissionRepository submissionRepository;
    private final KycDocumentRepository documentRepository;
    private final ProfileRepository profileRepository;
    private final com.nightgals.profile.ProfileService profileService;
    private final UserRepository userRepository;
    private final StorageService storageService;
    private final UploadValidator uploadValidator;
    private final AppProperties appProperties;

    /**
     * Opens a verification attempt, or updates the details of the one already in
     * DRAFT so a user who mistyped their name does not have to re-upload images.
     */
    @Transactional
    public KycSubmissionResponse startOrUpdate(User user, KycSubmissionRequest request) {
        // Submitting identity documents is unambiguously creator intent.
        profileService.upgradeToCreatorIfNeeded(user);
        Profile profile = profileRepository.findByUserId(user.getId())
                .orElseThrow(() -> ApiException.badRequest("profile_required",
                        "Create your profile before submitting identity documents"));

        if (user.getVerificationStatus() == VerificationStatus.APPROVED) {
            throw ApiException.conflict("already_verified", "This account is already verified");
        }
        if (submissionRepository.existsByUserIdAndStatus(user.getId(), KycStatus.PENDING_REVIEW)) {
            throw ApiException.conflict("review_in_progress",
                    "You already have a submission awaiting review");
        }

        requireAdult(request.dateOfBirth());
        requireMatchingDateOfBirth(request.dateOfBirth(), profile.getDateOfBirth());

        String normalisedNumber = request.documentNumber().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        String hash = hashDocumentNumber(normalisedNumber);

        if (submissionRepository.isDocumentAlreadyApprovedForAnotherUser(hash, user.getId())) {
            // Deliberately vague: confirming which document is in use would leak
            // information about another member.
            throw ApiException.conflict("duplicate_document",
                    "This document cannot be used to verify this account. Contact support.");
        }

        KycSubmission submission = submissionRepository
                .findFirstByUserIdAndStatus(user.getId(), KycStatus.DRAFT)
                .orElseGet(() -> KycSubmission.builder().user(user).status(KycStatus.DRAFT).build());

        // Changing document type invalidates images uploaded for the old type.
        if (submission.getDocumentType() != null && submission.getDocumentType() != request.documentType()) {
            submission.getDocuments().forEach(doc -> storageService.delete(doc.getStorageKey()));
            submission.getDocuments().clear();
        }

        submission.setDocumentType(request.documentType());
        submission.setFullName(request.fullName().trim());
        submission.setDateOfBirth(request.dateOfBirth());
        submission.setCountryOfIssue(request.countryOfIssue().toUpperCase(Locale.ROOT));
        submission.setDocumentNumberHash(hash);
        submission.setDocumentNumberLast4(lastFour(normalisedNumber));

        return KycSubmissionResponse.of(submissionRepository.save(submission));
    }

    /** Attaches one image. Re-uploading the same kind replaces the previous file. */
    @Transactional
    public KycSubmissionResponse uploadDocument(User user, DocumentKind kind, MultipartFile file) {
        KycSubmission submission = requireDraft(user);

        if (!submission.getDocumentType().requiredKinds().contains(kind)) {
            throw ApiException.badRequest("unexpected_document",
                    kind + " is not required for " + submission.getDocumentType()
                            + ". Expected: " + submission.getDocumentType().requiredKinds());
        }

        uploadValidator.validateImage(file);

        documentRepository.findBySubmissionIdAndKind(submission.getId(), kind).ifPresent(existing -> {
            storageService.delete(existing.getStorageKey());
            submission.getDocuments().remove(existing);
            documentRepository.delete(existing);
            documentRepository.flush();
        });

        StoredFile stored = storageService.store(file, "kyc/" + submission.getId());

        KycDocument document = KycDocument.builder()
                .submission(submission)
                .kind(kind)
                .storageKey(stored.storageKey())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .checksumSha256(stored.checksumSha256())
                .build();
        submission.getDocuments().add(document);
        documentRepository.save(document);

        log.info("KYC document {} uploaded for submission {}", kind, submission.getId());
        return KycSubmissionResponse.of(submission);
    }

    /** Hands the submission to the review queue. After this the user cannot edit it. */
    @Transactional
    public KycSubmissionResponse submitForReview(User user) {
        KycSubmission submission = requireDraft(user);

        if (!submission.hasAllRequiredDocuments()) {
            throw ApiException.badRequest("documents_missing",
                    "Upload all required documents first: " + submission.getDocumentType().requiredKinds());
        }

        submission.setStatus(KycStatus.PENDING_REVIEW);
        submission.setSubmittedAt(Instant.now());

        User managed = userRepository.findById(user.getId()).orElseThrow();
        managed.setVerificationStatus(VerificationStatus.PENDING_REVIEW);

        log.info("KYC submission {} queued for review", submission.getId());
        return KycSubmissionResponse.of(submission);
    }

    @Transactional(readOnly = true)
    public KycSubmissionResponse getCurrent(UUID userId) {
        return submissionRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .map(KycSubmissionResponse::of)
                .orElseThrow(() -> ApiException.notFound("Verification submission"));
    }

    @Transactional(readOnly = true)
    public List<KycSubmissionResponse> getHistory(UUID userId) {
        return submissionRepository.findAll().stream()
                .filter(s -> s.getUser().getId().equals(userId))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(KycSubmissionResponse::of)
                .toList();
    }

    /** Lets a user pull a queued submission back so they can fix and resend it. */
    @Transactional
    public KycSubmissionResponse withdraw(User user) {
        KycSubmission submission = submissionRepository
                .findFirstByUserIdAndStatus(user.getId(), KycStatus.PENDING_REVIEW)
                .orElseThrow(() -> ApiException.notFound("Pending submission"));

        submission.setStatus(KycStatus.DRAFT);
        submission.setSubmittedAt(null);

        User managed = userRepository.findById(user.getId()).orElseThrow();
        managed.setVerificationStatus(VerificationStatus.UNVERIFIED);

        return KycSubmissionResponse.of(submission);
    }

    private KycSubmission requireDraft(User user) {
        return submissionRepository.findFirstByUserIdAndStatus(user.getId(), KycStatus.DRAFT)
                .orElseThrow(() -> ApiException.badRequest("no_draft",
                        "Start a verification with POST /api/v1/me/kyc first"));
    }

    private void requireAdult(LocalDate dateOfBirth) {
        if (Period.between(dateOfBirth, LocalDate.now()).getYears() < appProperties.minimumAge()) {
            throw ApiException.badRequest("underage",
                    "You must be at least " + appProperties.minimumAge() + " to be verified");
        }
    }

    private void requireMatchingDateOfBirth(LocalDate stated, LocalDate onProfile) {
        if (!stated.equals(onProfile)) {
            throw ApiException.badRequest("dob_mismatch",
                    "Date of birth must match the one on your profile");
        }
    }

    /**
     * Peppered so that leaking the table alone does not let an attacker confirm a
     * guessed document number - national ID formats are short and enumerable.
     */
    private String hashDocumentNumber(String normalisedNumber) {
        return Hashing.sha256(appProperties.documentHashPepper() + ":" + normalisedNumber);
    }

    private String lastFour(String value) {
        return value.length() <= 4 ? value : value.substring(value.length() - 4);
    }
}
