package com.nightgals;

import com.nightgals.kyc.DocumentKind;
import com.nightgals.kyc.DocumentType;
import com.nightgals.kyc.KycService;
import com.nightgals.kyc.KycReviewService;
import com.nightgals.kyc.dto.KycReviewRequest;
import com.nightgals.kyc.dto.KycSubmissionRequest;
import com.nightgals.media.ContentTier;
import com.nightgals.media.MediaService;
import com.nightgals.media.MediaType;
import com.nightgals.profile.Gender;
import com.nightgals.profile.ProfileService;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.common.ApiException;
import com.nightgals.user.Role;
import com.nightgals.user.AccountType;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.UserStatus;
import com.nightgals.user.VerificationStatus;
import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.kyc.RejectionReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Walks the product's central promise end to end: an unverified account cannot
 * post, and only an administrator's approval changes that.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class OnboardingFlowTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired KycService kycService;
    @Autowired KycReviewService reviewService;
    @Autowired MediaService mediaService;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("A member can only post media after an admin approves their ID")
    void fullOnboardingFlow() {
        User member = registerMember();

        // 1. Unverified: posting is refused.
        assertThatThrownBy(() -> mediaService.upload(member, MediaType.PHOTO, photo(), null, ContentTier.EXCLUSIVE, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Verify your identity");

        // 2. Profile, then KYC details.
        profileService.createOrUpdate(member, new ProfileRequest(
                "Amina", "Afrobeats and rooftop bars", LocalDate.of(1998, 4, 12),
                Gender.FEMALE, "Douala", "Cameroon", null, null));

        var submission = kycService.startOrUpdate(member, new KycSubmissionRequest(
                DocumentType.NATIONAL_ID, "Amina Wanjiru Kamau",
                LocalDate.of(1998, 4, 12), "KE", "A012345678"));
        assertThat(submission.readyToSubmit()).isFalse();
        assertThat(submission.documentNumberLast4()).isEqualTo("5678");

        // 3. Cannot skip ahead while documents are missing.
        assertThatThrownBy(() -> kycService.submitForReview(member))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Upload all required documents");

        kycService.uploadDocument(member, DocumentKind.ID_FRONT, photo());
        kycService.uploadDocument(member, DocumentKind.ID_BACK, photo());
        var ready = kycService.uploadDocument(member, DocumentKind.SELFIE, photo());
        assertThat(ready.readyToSubmit()).isTrue();
        assertThat(ready.missingDocuments()).isEmpty();

        var queued = kycService.submitForReview(member);
        assertThat(queued.status().name()).isEqualTo("PENDING_REVIEW");
        assertThat(reload(member).getVerificationStatus()).isEqualTo(VerificationStatus.PENDING_REVIEW);

        // 4. Still cannot post while waiting.
        assertThatThrownBy(() -> mediaService.upload(reload(member), MediaType.PHOTO, photo(), null, ContentTier.EXCLUSIVE, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("still being reviewed");

        // 5. An admin approves.
        User admin = createAdmin();
        reviewService.review(queued.id(), new KycReviewRequest(true, null, "ID matches selfie"), admin);
        assertThat(reload(member).getVerificationStatus()).isEqualTo(VerificationStatus.APPROVED);

        // 6. Now the upload succeeds, and publishes immediately - KYC was the gate.
        var uploaded = mediaService.upload(reload(member), MediaType.PHOTO, photo(), "Saturday night", ContentTier.EXCLUSIVE, null);
        assertThat(uploaded.status().name()).isEqualTo("APPROVED");
        assertThat(uploaded.locked()).isFalse();
        assertThat(uploaded.primary()).isTrue();

        // 7. And it is visible straight away.
        assertThat(mediaService.listPublic(member.getId(), admin)).hasSize(1);
    }

    @Test
    @DisplayName("A rejected submission blocks posting and records the reason")
    void rejectionKeepsAccountLocked() {
        User member = registerMember();
        profileService.createOrUpdate(member, new ProfileRequest(
                "Brian", null, LocalDate.of(1995, 1, 1),
                Gender.MALE, "Douala", "Cameroon", null, null));
        kycService.startOrUpdate(member, new KycSubmissionRequest(
                DocumentType.PASSPORT, "Brian Otieno", LocalDate.of(1995, 1, 1), "KE", "P99887766"));
        kycService.uploadDocument(member, DocumentKind.PASSPORT_PAGE, photo());
        kycService.uploadDocument(member, DocumentKind.SELFIE, photo());
        var queued = kycService.submitForReview(member);

        User admin = createAdmin();
        var decided = reviewService.review(queued.id(),
                new KycReviewRequest(false, RejectionReason.DOCUMENT_UNREADABLE, "Blurry"), admin);

        assertThat(decided.rejectionReason()).isEqualTo(RejectionReason.DOCUMENT_UNREADABLE);
        assertThat(reload(member).getVerificationStatus()).isEqualTo(VerificationStatus.REJECTED);
        assertThatThrownBy(() -> mediaService.upload(reload(member), MediaType.PHOTO, photo(), null, ContentTier.EXCLUSIVE, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not successful");
    }

    @Test
    @DisplayName("KYC date of birth must match the profile")
    void kycRejectsMismatchedDateOfBirth() {
        User member = registerMember();
        profileService.createOrUpdate(member, new ProfileRequest(
                "Cate", null, LocalDate.of(1999, 6, 6),
                Gender.FEMALE, "Buea", "Cameroon", null, null));

        assertThatThrownBy(() -> kycService.startOrUpdate(member, new KycSubmissionRequest(
                DocumentType.NATIONAL_ID, "Cate Njeri", LocalDate.of(1997, 6, 6), "KE", "12345678")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must match the one on your profile");
    }

    @Test
    @DisplayName("Under-18s are refused at the profile stage")
    void underageProfileRejected() {
        User member = registerMember();
        assertThatThrownBy(() -> profileService.createOrUpdate(member, new ProfileRequest(
                "Kid", null, LocalDate.now().minusYears(16),
                Gender.FEMALE, "Douala", "Cameroon", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least 18");
    }

    @Test
    @DisplayName("A reviewer cannot approve their own submission")
    void selfReviewRefused() {
        // Unverified on purpose: this staff member is going through KYC themselves.
        User staff = createStaff(VerificationStatus.UNVERIFIED);
        profileService.createOrUpdate(staff, new ProfileRequest(
                "Mod", null, LocalDate.of(1990, 2, 2),
                Gender.FEMALE, "Douala", "Cameroon", null, null));
        kycService.startOrUpdate(staff, new KycSubmissionRequest(
                DocumentType.PASSPORT, "Mod Erator", LocalDate.of(1990, 2, 2), "KE", "P11112222"));
        kycService.uploadDocument(staff, DocumentKind.PASSPORT_PAGE, photo());
        kycService.uploadDocument(staff, DocumentKind.SELFIE, photo());
        var queued = kycService.submitForReview(staff);

        assertThatThrownBy(() -> reviewService.review(queued.id(),
                new KycReviewRequest(true, null, null), reload(staff)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot review your own");
    }

    // ------------------------------------------------------------- helpers

    private User registerMember() {
        String email = "member-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private User createAdmin() {
        return createStaff(VerificationStatus.APPROVED);
    }

    private User createStaff(VerificationStatus verificationStatus) {
        return userRepository.save(User.builder()
                .email("admin-" + UUID.randomUUID() + "@nightgals.local")
                .username("Staff" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .passwordHash(passwordEncoder.encode("admin-password-1"))
                .role(Role.ADMIN)
                .accountType(AccountType.CREATOR)
                .status(UserStatus.ACTIVE)
                .verificationStatus(verificationStatus)
                .emailVerified(true)
                .build());
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private MockMultipartFile photo() {
        return new MockMultipartFile("file", "id.jpg", "image/jpeg", new byte[]{1, 2, 3, 4});
    }
}
