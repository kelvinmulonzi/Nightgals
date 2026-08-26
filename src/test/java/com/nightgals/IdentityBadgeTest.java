package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.common.ApiException;
import com.nightgals.kyc.KycService;
import com.nightgals.profile.Gender;
import com.nightgals.profile.ProfileService;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.user.AccountType;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.VerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the verified badge claims.
 *
 * <p>The badge says a human checked identity documents. The publishing gate says
 * an account may post. They were the same field until identity checks became
 * optional, at which point the badge started making a claim nobody had checked.
 *
 * <p>Runs with checks switched off - the configuration production is actually in
 * - because that is the case the old code got wrong.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "nightgals.app.kyc-required=false")
@Transactional
class IdentityBadgeTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired KycService kycService;
    @Autowired UserRepository userRepository;
    @Autowired com.nightgals.profile.ProfileRepository profileRepository;

    @Test
    @DisplayName("Saving a profile grants publishing rights but not a badge")
    void approvalIsNotABadge() {
        User creator = creatorWithProfile();

        User saved = reload(creator);
        // The gate opened, because that is what keeps the platform usable with
        // identity checks off.
        assertThat(saved.getVerificationStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(saved.isApproved()).isTrue();
        // The badge did not, because no document was ever looked at.
        assertThat(saved.isIdentityVerified()).isFalse();
        assertThat(profileService.getOwn(saved.getId()).verified()).isFalse();
    }

    @Test
    @DisplayName("Documents can be submitted even though verification is optional")
    void submissionIsOpenWhileOptional() {
        User creator = creatorWithProfile();

        // Used to be refused outright with kyc_disabled.
        var submission = kycService.startOrUpdate(reload(creator), submissionRequest());

        assertThat(submission).isNotNull();
    }

    @Test
    @DisplayName("Volunteering a document does not cost an account its publishing rights")
    void submittingDoesNotStripApproval() {
        User creator = creatorWithProfile();
        kycService.startOrUpdate(reload(creator), submissionRequest());

        // The hazard the old kyc_disabled guard existed to prevent: an account
        // approved on its profile being moved to PENDING_REVIEW for cooperating.
        assertThat(reload(creator).getVerificationStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(reload(creator).isApproved()).isTrue();
    }

    @Test
    @DisplayName("An account already wearing a badge is not asked for documents again")
    void alreadyVerifiedIsAskedOfTheBadge() {
        User creator = creatorWithProfile();
        User managed = reload(creator);
        managed.setIdentityVerifiedAt(Instant.now());
        userRepository.save(managed);

        assertThatThrownBy(() -> kycService.startOrUpdate(reload(creator), submissionRequest()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already verified");
    }

    @Test
    @DisplayName("A viewer can set a picture without a profile and without entering the feed")
    void viewerGetsAnAvatar() {
        User viewer = register(AccountType.VIEWER);

        var profile = profileService.setAvatar(reload(viewer), image());

        assertThat(profile.profilePhotoUrl()).isNotNull();
        // Created with nothing on it but the picture.
        assertThat(profile.age()).isNull();
        // And kept out of browse, which is for creators.
        assertThat(profile.discoverable()).isFalse();
    }

    @Test
    @DisplayName("A creator's own picture upload still leaves them discoverable")
    void creatorAvatarStaysDiscoverable() {
        User creator = creatorWithProfile();

        var profile = profileService.setAvatar(reload(creator), image());

        assertThat(profile.discoverable()).isTrue();
        assertThat(profile.profilePhotoUrl()).isNotNull();
    }

    @Test
    @DisplayName("A picture alone does not finish onboarding for a creator")
    void photoDoesNotCompleteAProfile() {
        User creator = register(AccountType.CREATOR);

        // Setting a picture creates the profile row. That row is not a profile:
        // reading its mere presence as "complete" told her onboarding was over
        // while every publishing guard still refused her.
        profileService.setAvatar(reload(creator), image());

        assertThat(profileRepository.isCompleteForUser(creator.getId())).isFalse();

        // And filling the form in does finish it.
        profileService.createOrUpdate(reload(creator), new ProfileRequest(
                null, "Out most weekends", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Douala", "Cameroon", null, null));

        assertThat(profileRepository.isCompleteForUser(creator.getId())).isTrue();
    }

    // ------------------------------------------------------------- helpers

    private com.nightgals.kyc.dto.KycSubmissionRequest submissionRequest() {
        return new com.nightgals.kyc.dto.KycSubmissionRequest(
                com.nightgals.kyc.DocumentType.NATIONAL_ID,
                "AB123456",
                LocalDate.of(1996, 5, 5),
                "Ada",
                "Nkemelu");
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("file", "me.jpg", "image/jpeg", new byte[] {1, 2, 3, 4});
    }

    private User register(AccountType type) {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", type, null), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private User creatorWithProfile() {
        User user = register(AccountType.CREATOR);
        profileService.createOrUpdate(user, new ProfileRequest(
                null, "Out most weekends", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Douala", "Cameroon", null, null));
        return user;
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
