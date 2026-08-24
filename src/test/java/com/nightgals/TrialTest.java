package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
import com.nightgals.billing.EntitlementService;
import com.nightgals.media.ContentTier;
import com.nightgals.media.MediaRepository;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Seven free days on every new account.
 *
 * <p>Full access, both sides: a viewer sees premium content without paying, a
 * creator publishes without a package. It is an expiry rather than a flag, so
 * nothing has to switch it off.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "nightgals.monetization.free-trial=P7D",
        "nightgals.creator-packages.enabled=true",
})
@Transactional
class TrialTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired MediaService mediaService;
    @Autowired BillingService billingService;
    @Autowired EntitlementService entitlementService;
    @Autowired MediaRepository mediaRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("A new account gets seven days, measured from now")
    void trialIsSevenDays() {
        User user = register(AccountType.VIEWER);

        assertThat(user.isOnTrial()).isTrue();
        // Seven days from now, give or take the time this test took to run.
        assertThat(user.getTrialEndsAt())
                .isAfter(Instant.now().plus(6, ChronoUnit.DAYS))
                .isBefore(Instant.now().plus(8, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("A viewer on trial sees premium content without paying")
    void trialOpensPaidContent() {
        User creator = approvedCreator();
        UUID item = publish(creator, 9_000L);
        User viewer = register(AccountType.VIEWER);

        assertThat(entitlementService.canView(viewer, media(item))).isTrue();
        assertThat(mediaService.listPublic(creator.getId(), viewer))
                .allSatisfy(m -> assertThat(m.locked()).isFalse());
    }

    @Test
    @DisplayName("A creator on trial publishes without a package")
    void trialOpensPublishing() {
        User creator = approvedCreator();

        // Packages are on, and she has bought nothing.
        assertThat(billingService.entitlements(reload(creator)).onTrial()).isTrue();
        publish(creator, 3_000L);
    }

    @Test
    @DisplayName("When the trial runs out, the paywall closes")
    void expiredTrialClosesAccess() {
        User creator = approvedCreator();
        UUID item = publish(creator, 9_000L);
        User viewer = register(AccountType.VIEWER);

        assertThat(entitlementService.canView(viewer, media(item))).isTrue();

        expireTrial(viewer);

        assertThat(entitlementService.canView(reload(viewer), media(item))).isFalse();
        assertThatThrownBy(() -> mediaService.download(item, reload(viewer)))
                .hasMessageContaining("Unlock this item");
    }

    @Test
    @DisplayName("An expired creator trial stops publishing until a package is bought")
    void expiredTrialStopsPublishing() {
        User creator = approvedCreator();
        publish(creator, 3_000L);

        expireTrial(creator);

        assertThatThrownBy(() -> publish(reload(creator), 3_000L))
                .hasMessageContaining("Choose a package");

        // ...and buying one puts her straight back to work.
        var checkout = billingService.buyCreatorPackage(reload(creator), "PRO");
        billingService.settle(checkout.purchase().id(), null);
        publish(reload(creator), 3_000L);
    }

    @Test
    @DisplayName("The trial never covers anonymous visitors")
    void anonymousIsNotOnTrial() {
        User creator = approvedCreator();
        UUID item = publish(creator, 9_000L);

        assertThat(entitlementService.canView(null, media(item))).isFalse();
    }

    // ------------------------------------------------------------- helpers

    private void expireTrial(User user) {
        User managed = reload(user);
        managed.setTrialEndsAt(Instant.now().minus(1, ChronoUnit.DAYS));
        userRepository.saveAndFlush(managed);
    }

    private UUID publish(User creator, Long priceMinor) {
        return mediaService.upload(reload(creator), MediaType.PHOTO, photo(),
                null, ContentTier.EXCLUSIVE, priceMinor).id();
    }

    private com.nightgals.media.MediaAsset media(UUID id) {
        return mediaRepository.findById(id).orElseThrow();
    }

    private MockMultipartFile photo() {
        return new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private User approvedCreator() {
        User user = register(AccountType.CREATOR);
        profileService.createOrUpdate(user, new ProfileRequest(
                null, "Here for the weekend", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Douala", "Cameroon", null, null));

        User managed = reload(user);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        User saved = userRepository.saveAndFlush(managed);

        // Burn the forced-free profile picture.
        mediaService.upload(saved, MediaType.PHOTO, photo(), null, ContentTier.FREE, null);
        return reload(saved);
    }

    private User register(AccountType type) {
        String email = "trial-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", type, null), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
