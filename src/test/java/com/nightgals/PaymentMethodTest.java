package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
import com.nightgals.billing.PaymentChoice;
import com.nightgals.billing.PaymentProvider;
import com.nightgals.billing.PaymentProviders;
import com.nightgals.billing.PurchaseRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Several payment methods at once, chosen per purchase.
 *
 * <p>Stripe is deliberately not one of them here: it needs live credentials and
 * would reach the network. What is being tested is the part that has nothing to
 * do with any particular provider - that a checkout can name a method, that
 * naming none still works, and that changing one's mind repoints the purchase -
 * so two providers needing no credentials stand in for the real pair.
 *
 * <p>{@code auto} and {@code manual} are a useful stand-in precisely because
 * they behave oppositely: one settles inside the checkout call, the other leaves
 * the purchase PENDING, which is the same split as card versus Mobile Money.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "nightgals.monetization.providers=manual,auto",
        "nightgals.monetization.default-provider=manual",
        "nightgals.creator-packages.enabled=false",
})
@Transactional
class PaymentMethodTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired MediaService mediaService;
    @Autowired BillingService billingService;
    @Autowired PaymentProviders paymentProviders;
    @Autowired UserRepository userRepository;
    @Autowired PurchaseRepository purchaseRepository;

    @Test
    @DisplayName("Both configured methods are on offer, in the order configured")
    void bothAreListed() {
        var methods = billingService.paymentMethods();

        assertThat(methods).extracting("code").containsExactly("MANUAL", "AUTO");
        assertThat(methods).extracting("label").doesNotContainNull();
    }

    @Test
    @DisplayName("Exactly one method is the default, and it is the configured one")
    void oneDefault() {
        var methods = billingService.paymentMethods();

        assertThat(methods).filteredOn("isDefault", true).extracting("code")
                .containsExactly("MANUAL");
    }

    @Test
    @DisplayName("A checkout naming a method uses that one, not the default")
    void methodIsHonoured() {
        User creator = approvedCreator();
        UUID item = paidItem(creator);

        var checkout = billingService.unlockMedia(viewer(), item, PaymentChoice.of("AUTO", null));

        // AUTO settles inside the call; MANUAL - the default - would not have.
        assertThat(checkout.purchase().provider()).isEqualTo("AUTO");
        assertThat(checkout.purchase().status()).isEqualTo(PurchaseStatus.COMPLETED);
        assertThat(checkout.action()).isEqualTo(PaymentProvider.PaymentInstruction.Action.NONE);
    }

    @Test
    @DisplayName("A checkout naming no method falls back to the default")
    void defaultIsUsedWhenUnspecified() {
        User creator = approvedCreator();

        var checkout = billingService.unlockMedia(viewer(), paidItem(creator), PaymentChoice.none());

        assertThat(checkout.purchase().provider()).isEqualTo("MANUAL");
        assertThat(checkout.purchase().status()).isEqualTo(PurchaseStatus.PENDING);
        assertThat(checkout.action()).isEqualTo(PaymentProvider.PaymentInstruction.Action.MANUAL);
    }

    @Test
    @DisplayName("An unknown method is refused rather than quietly charged another way")
    void unknownMethodIsRefused() {
        User creator = approvedCreator();
        UUID item = paidItem(creator);

        assertThatThrownBy(() ->
                billingService.unlockMedia(viewer(), item, PaymentChoice.of("BITCOIN", null)))
                .hasMessageContaining("No such payment method");
    }

    @Test
    @DisplayName("Method codes are case-insensitive, so clients need not shout")
    void methodIsCaseInsensitive() {
        assertThat(paymentProviders.resolve("auto").name()).isEqualTo("AUTO");
        assertThat(paymentProviders.resolve(" Manual ").name()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("Changing method on a pending purchase repoints it instead of opening a second")
    void switchingMethodRepointsThePurchase() {
        User creator = approvedCreator();
        UUID item = paidItem(creator);
        User viewer = viewer();

        var first = billingService.unlockMedia(viewer, item, PaymentChoice.of("MANUAL", null));
        assertThat(first.purchase().status()).isEqualTo(PurchaseStatus.PENDING);

        // Second thoughts: the same purchase, paid a different way.
        var second = billingService.unlockMedia(reload(viewer), item, PaymentChoice.of("AUTO", null));

        assertThat(second.purchase().id()).isEqualTo(first.purchase().id());
        assertThat(second.purchase().provider()).isEqualTo("AUTO");
        assertThat(second.purchase().status()).isEqualTo(PurchaseStatus.COMPLETED);

        // The row itself moved, not just the response - otherwise the reconcilers,
        // which sweep by provider, would still be chasing this under the old one.
        var stored = purchaseRepository.findById(first.purchase().id()).orElseThrow();
        assertThat(stored.getProvider()).isEqualTo("AUTO");
        assertThat(purchaseRepository.findByUserIdOrderByCreatedAtDesc(
                viewer.getId(), org.springframework.data.domain.Pageable.ofSize(10))
                .getTotalElements()).isEqualTo(1);
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

        // Burn the forced-free profile picture, so paidItem() is genuinely paid.
        mediaService.upload(reload(approved), MediaType.PHOTO, photo(), null, ContentTier.FREE, null);
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

    private UUID paidItem(User creator) {
        return mediaService.upload(reload(creator), MediaType.PHOTO, photo(),
                null, ContentTier.EXCLUSIVE, null).id();
    }
}
