package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
import com.nightgals.billing.CreatorPackageService;
import com.nightgals.billing.EntitlementService;
import com.nightgals.billing.PaymentProvider;
import com.nightgals.billing.PurchaseStatus;
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

/**
 * The happy path: nobody has to settle anything by hand.
 *
 * <p>With the auto-settling provider a purchase is complete by the time the
 * checkout response is written, so access is open on the next request rather
 * than after an administrator confirms it. No money is collected - see
 * {@link com.nightgals.billing.AutoSettlePaymentProvider}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "nightgals.monetization.providers=auto",
        "nightgals.creator-packages.enabled=true",
})
@Transactional
class AutoSettleTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired MediaService mediaService;
    @Autowired BillingService billingService;
    @Autowired EntitlementService entitlementService;
    @Autowired CreatorPackageService creatorPackageService;
    @Autowired UserRepository userRepository;
    @Autowired com.nightgals.media.MediaRepository mediaRepository;

    @Test
    @DisplayName("Buying an item opens it straight away")
    void unlockIsImmediate() {
        User creator = approvedCreator();
        java.util.UUID item = paidItem(creator);
        User viewer = viewer();

        var checkout = billingService.unlockMedia(viewer, item);

        assertThat(checkout.purchase().status()).isEqualTo(PurchaseStatus.COMPLETED);
        assertThat(checkout.purchase().completedAt()).isNotNull();
        // NONE, so the client knows there is nothing to poll and nothing to show.
        assertThat(checkout.action()).isEqualTo(PaymentProvider.PaymentInstruction.Action.NONE);

        assertThat(entitlementService.canView(reload(viewer), asset(item))).isTrue();
    }

    @Test
    @DisplayName("A package is active on the next request, with no admin in the loop")
    void packageIsImmediate() {
        User creator = approvedCreator();

        var checkout = billingService.buyCreatorPackage(reload(creator), "BLACK_DIAMOND");
        assertThat(checkout.purchase().status()).isEqualTo(PurchaseStatus.COMPLETED);

        var status = creatorPackageService.status(reload(creator));
        assertThat(status.active()).isTrue();
        assertThat(status.canPostPhotos()).isTrue();
        assertThat(status.canPostVideos()).isTrue();

        // And publishing actually works, which is the point of the whole path.
        mediaService.upload(reload(creator), MediaType.PHOTO, photo(), null, ContentTier.EXCLUSIVE, null);
        mediaService.upload(reload(creator), MediaType.VIDEO, video(), null, ContentTier.EXCLUSIVE, null);
    }

    @Test
    @DisplayName("The buyer owns it immediately - auto-settling skips the human, not the ledger")
    void ownershipIsRecorded() {
        User creator = approvedCreator();
        billingService.unlockMedia(viewer(), paidItem(creator));

        User buyer = viewer();
        billingService.unlockMedia(buyer, paidItem(creator));

        assertThat(billingService.entitlements(reload(buyer)).unlockedItems()).isEqualTo(1);
    }

    @Test
    @DisplayName("Buying the same item twice is refused rather than charged twice")
    void secondUnlockIsRefused() {
        User creator = approvedCreator();
        java.util.UUID item = paidItem(creator);
        User viewer = viewer();

        billingService.unlockMedia(viewer, item);

        // The first purchase settled instantly, so the second attempt is caught by
        // the already-owned check rather than reusing a pending purchase.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> billingService.unlockMedia(reload(viewer), item))
                .hasMessageContaining("already have this");
    }

    // ------------------------------------------------------------- helpers

    private User approvedCreator() {
        String email = "creator-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();

        profileService.createOrUpdate(user, new ProfileRequest(
                null, "Here for the weekend", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Douala", "Cameroon", null, null));

        User managed = reload(user);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        User approved = userRepository.saveAndFlush(managed);

        // Packages are on for this class, so publishing needs one. The auto
        // provider settles it inside this call.
        billingService.buyCreatorPackage(approved, "BLACK_DIAMOND");

        // Burn the forced-free profile picture, so paidItem() is genuinely paid.
        mediaService.upload(reload(approved), MediaType.PHOTO, photo(),
                null, ContentTier.FREE, null);
        return reload(approved);
    }

    private User viewer() {
        String email = "viewer-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.VIEWER, null), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private MockMultipartFile photo() {
        return new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private MockMultipartFile video() {
        return new MockMultipartFile("file", "v.mp4", "video/mp4", new byte[] {4, 5, 6});
    }

    /** A locked item on this creator's profile, past the forced-free first photo. */
    private java.util.UUID paidItem(User creator) {
        return mediaService.upload(reload(creator), MediaType.PHOTO, photo(),
                null, ContentTier.EXCLUSIVE, null).id();
    }

    private com.nightgals.media.MediaAsset asset(java.util.UUID id) {
        return mediaRepository.findById(id).orElseThrow();
    }
}
