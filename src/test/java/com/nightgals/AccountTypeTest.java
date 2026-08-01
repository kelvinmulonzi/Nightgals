package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
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
import com.nightgals.user.AccountUpgradeService;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Viewers and creators are different accounts with different journeys. A viewer
 * is never asked for a profile, a date of birth or an identity document.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountTypeTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired KycService kycService;
    @Autowired MediaService mediaService;
    @Autowired BillingService billingService;
    @Autowired AccountUpgradeService accountUpgradeService;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("Registration defaults to a viewer account")
    void defaultsToViewer() {
        var response = authService.register(new RegisterRequest(email(), "correct-horse-9", null), null);
        assertThat(response.auth().accountType()).isEqualTo(AccountType.VIEWER);
    }

    @Test
    @DisplayName("A viewer is told to browse, not to complete onboarding")
    void viewerNextStepIsBrowse() {
        User viewer = register(AccountType.VIEWER);
        MeResponse me = MeResponse.of(viewer, false);

        assertThat(me.accountType()).isEqualTo(AccountType.VIEWER);
        assertThat(me.nextStep()).isEqualTo("BROWSE");
        assertThat(me.canPostMedia()).isFalse();
    }

    @Test
    @DisplayName("A creator is walked through profile then verification")
    void creatorNextStepIsOnboarding() {
        User creator = register(AccountType.CREATOR);
        assertThat(MeResponse.of(creator, false).nextStep()).isEqualTo("CREATE_PROFILE");
        assertThat(MeResponse.of(creator, true).nextStep()).isEqualTo("SUBMIT_KYC");
    }

    @Test
    @DisplayName("A viewer is never *asked* for a profile - but submitting one makes them a creator")
    void submittingAProfileUpgrades() {
        User viewer = register(AccountType.VIEWER);
        assertThat(MeResponse.of(viewer, false).nextStep()).isEqualTo("BROWSE");

        // Nobody fills in a public profile by accident, so this is treated as intent
        // rather than bounced back with an error.
        profileService.createOrUpdate(viewer, new ProfileRequest(
                null, null, LocalDate.of(1996, 1, 1), Gender.FEMALE, "Nairobi", "Kenya", null, null, null));

        User after = reload(viewer);
        assertThat(after.getAccountType()).isEqualTo(AccountType.CREATOR);
        assertThat(MeResponse.of(after, true).nextStep()).isEqualTo("SUBMIT_KYC");
    }

    @Test
    @DisplayName("A viewer who only browses and pays is never upgraded")
    void browsingNeverUpgrades() {
        User creator = approvedCreator();
        User viewer = register(AccountType.VIEWER);

        mediaService.listPublic(creator.getId(), viewer);
        var checkout = billingService.unlockProfile(viewer, creator.getId());
        billingService.settle(checkout.purchase().id(), "STILL-A-VIEWER");
        mediaService.listPublic(creator.getId(), reload(viewer));

        assertThat(reload(viewer).getAccountType()).isEqualTo(AccountType.VIEWER);
        assertThat(MeResponse.of(reload(viewer), false).nextStep()).isEqualTo("BROWSE");
    }

    @Test
    @DisplayName("Starting KYC also upgrades, for a client that skips the profile screen")
    void startingKycUpgrades() {
        User viewer = register(AccountType.VIEWER);
        profileService.createOrUpdate(viewer, new ProfileRequest(
                null, null, LocalDate.of(1996, 1, 1), Gender.FEMALE, "Nairobi", "Kenya", null, null, null));

        kycService.startOrUpdate(reload(viewer), new KycSubmissionRequest(
                DocumentType.PASSPORT, "A Creator", LocalDate.of(1996, 1, 1), "KE", "P1234567"));

        assertThat(reload(viewer).getAccountType()).isEqualTo(AccountType.CREATOR);
    }

    @Test
    @DisplayName("A viewer can still pay for and watch a creator's content")
    void viewerCanConsume() {
        User creator = approvedCreator();
        // The first photo is the profile picture and is always FREE, so a second
        // one is needed for there to be anything behind the paywall.
        mediaService.upload(reload(creator), MediaType.PHOTO,
                new MockMultipartFile("file", "free.jpg", "image/jpeg", new byte[]{1}), null,
                ContentTier.FREE);
        mediaService.upload(reload(creator), MediaType.PHOTO,
                new MockMultipartFile("file", "paid.jpg", "image/jpeg", new byte[]{2}), null,
                ContentTier.EXCLUSIVE);

        User viewer = register(AccountType.VIEWER);
        assertThat(mediaService.listPublic(creator.getId(), viewer))
                .anySatisfy(m -> assertThat(m.locked()).isTrue());

        var checkout = billingService.unlockProfile(viewer, creator.getId());
        billingService.settle(checkout.purchase().id(), "VIEWER-PAYS");

        assertThat(mediaService.listPublic(creator.getId(), reload(viewer)))
                .allSatisfy(m -> assertThat(m.locked()).isFalse());
    }

    @Test
    @DisplayName("A viewer who wants to post upgrades, keeping handle and purchases")
    void viewerCanBecomeCreator() {
        User creator = approvedCreator();
        User viewer = register(AccountType.VIEWER);
        String handle = viewer.getUsername();

        var checkout = billingService.unlockProfile(viewer, creator.getId());
        billingService.settle(checkout.purchase().id(), "KEEP-ME");

        MeResponse upgraded = accountUpgradeService.becomeCreator(reload(viewer));
        assertThat(upgraded.accountType()).isEqualTo(AccountType.CREATOR);
        assertThat(upgraded.nextStep()).isEqualTo("CREATE_PROFILE");
        assertThat(upgraded.username()).isEqualTo(handle);

        // The unlock they paid for survives the change.
        assertThat(billingService.entitlements(reload(viewer)).unlockedMembers()).hasSize(1);

        // And the creator path is now open to them.
        profileService.createOrUpdate(reload(viewer), new ProfileRequest(
                null, null, LocalDate.of(1994, 3, 3), Gender.FEMALE, "Nairobi", "Kenya", null, null, null));
    }

    @Test
    @DisplayName("Upgrading twice is a no-op")
    void upgradeIsIdempotent() {
        User viewer = register(AccountType.VIEWER);
        accountUpgradeService.becomeCreator(viewer);
        assertThat(accountUpgradeService.becomeCreator(reload(viewer)).accountType())
                .isEqualTo(AccountType.CREATOR);
    }

    // ------------------------------------------------------------- helpers

    private String email() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private User register(AccountType type) {
        String e = email();
        authService.register(new RegisterRequest(e, "correct-horse-9", type), null);
        return userRepository.findByEmailIgnoreCase(e).orElseThrow();
    }

    private User approvedCreator() {
        User user = register(AccountType.CREATOR);
        profileService.createOrUpdate(user, new ProfileRequest(
                null, null, LocalDate.of(1996, 5, 5), Gender.FEMALE, "Nairobi", "Kenya", null, null, null));
        User managed = reload(user);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        return userRepository.save(managed);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
