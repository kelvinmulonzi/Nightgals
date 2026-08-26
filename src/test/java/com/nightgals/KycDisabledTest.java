package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.common.ApiException;
import com.nightgals.kyc.DocumentType;
import com.nightgals.kyc.KycService;
import com.nightgals.kyc.dto.KycSubmissionRequest;
import com.nightgals.media.ContentTier;
import com.nightgals.media.MediaService;
import com.nightgals.media.MediaType;
import com.nightgals.profile.Gender;
import com.nightgals.profile.ProfileService;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.user.AccountType;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.VerificationStatus;
import com.nightgals.user.dto.MeResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The platform with identity checks switched off.
 *
 * <p>Saving a profile is then the whole of onboarding: approval is granted at
 * that moment, so a creator goes straight from her details to paying for a
 * package with no document upload and nobody to wait for.
 *
 * <p>The rest of the suite runs with {@code kyc-required: true}, so this is the
 * only place the other half of the switch is exercised.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "nightgals.app.kyc-required=false")
@Transactional
class KycDisabledTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired KycService kycService;
    @Autowired MediaService mediaService;
    @Autowired UserRepository userRepository;
    @Autowired com.nightgals.user.AccountUpgradeService upgradeService;

    @Test
    @DisplayName("Saving a profile approves the account, with nothing else to do")
    void profileSaveIsTheWholeJourney() {
        User member = register();
        assertThat(member.getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);

        saveProfile(member);

        User after = reload(member);
        assertThat(after.getVerificationStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(after.isApproved()).isTrue();
    }

    @Test
    @DisplayName("Onboarding reports DONE, so the client sends her to pay")
    void nextStepSkipsTheDocumentScreens() {
        User member = register();
        saveProfile(member);

        MeResponse me = MeResponse.of(reload(member), true, false);

        assertThat(me.nextStep()).isEqualTo("DONE");
        assertThat(me.kycRequired()).isFalse();
        assertThat(me.canPostMedia()).isTrue();
    }

    @Test
    @DisplayName("She can publish straight away - no approval to wait for")
    void publishingWorksWithoutAnyReview() {
        User member = register();
        saveProfile(member);

        var uploaded = mediaService.upload(reload(member), MediaType.PHOTO,
                new MockMultipartFile("file", "shot.jpg", "image/jpeg", new byte[] {1, 2, 3, 4}),
                "First post", ContentTier.EXCLUSIVE, null);

        assertThat(uploaded).isNotNull();
    }

    @Test
    @DisplayName("Documents are accepted without un-approving her")
    void documentSubmissionKeepsHerApproved() {
        User member = register();
        saveProfile(member);

        // Submitting used to be refused outright, because accepting it moved her
        // to PENDING_REVIEW and took away rights nobody was going to give back.
        // Verifying is now voluntary - it earns the badge - so the submission is
        // accepted and the guarantee is kept by leaving her status alone instead.
        var submission = kycService.startOrUpdate(reload(member), new KycSubmissionRequest(
                DocumentType.NATIONAL_ID, "A Creator", LocalDate.of(1996, 1, 1), "CM", "12345678"));

        assertThat(submission).isNotNull();
        assertThat(reload(member).getVerificationStatus()).isEqualTo(VerificationStatus.APPROVED);
        // And she has not been handed a badge merely for uploading something.
        assertThat(reload(member).isIdentityVerified()).isFalse();
    }

    @Test
    @DisplayName("Date of birth stays editable - the approval checked nothing")
    void dateOfBirthIsNotFrozen() {
        User member = register();
        saveProfile(member);

        profileService.createOrUpdate(reload(member), new ProfileRequest(
                "A Creator", null, LocalDate.of(1995, 2, 2),
                Gender.FEMALE, "Douala", "Cameroon", null, null));

        assertThat(reload(member).getVerificationStatus()).isEqualTo(VerificationStatus.APPROVED);
    }

    @Test
    @DisplayName("A viewer who already had a profile is approved when she switches")
    void upgradingAnExistingProfileApproves() {
        // The state this reconstructs is the one that exists the moment the flag
        // is turned off on a running platform: an account with a profile that
        // was never approved, because approval used to need a moderator.
        //
        // There is no profile save left to trigger it, so the upgrade has to -
        // otherwise onboarding reports DONE while publishing stays shut, and the
        // client bounces between the studio and the guard forever.
        User viewer = register(AccountType.VIEWER);
        saveProfile(viewer);
        User managed = reload(viewer);
        managed.setAccountType(AccountType.VIEWER);
        managed.setVerificationStatus(VerificationStatus.UNVERIFIED);
        userRepository.saveAndFlush(managed);

        MeResponse me = upgradeService.becomeCreator(reload(viewer));

        assertThat(me.nextStep()).isEqualTo("DONE");
        assertThat(me.canPostMedia()).isTrue();
        assertThat(reload(viewer).getVerificationStatus()).isEqualTo(VerificationStatus.APPROVED);
    }

    // ------------------------------------------------------------- helpers

    private void saveProfile(User member) {
        profileService.createOrUpdate(member, new ProfileRequest(
                "A Creator", null, LocalDate.of(1996, 1, 1),
                Gender.FEMALE, "Douala", "Cameroon", null, null));
    }

    private User register() {
        return register(AccountType.CREATOR);
    }

    private User register(AccountType type) {
        String email = "nokyc-" + UUID.randomUUID() + "@example.com";
        var auth = authService.register(
                new RegisterRequest(email, "correct-horse-9", type, null), null);
        return userRepository.findById(auth.auth().userId()).orElseThrow();
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
