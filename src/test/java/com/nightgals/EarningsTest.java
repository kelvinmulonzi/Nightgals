package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
import com.nightgals.common.ApiException;
import com.nightgals.earnings.EarningRepository;
import com.nightgals.earnings.EarningStatus;
import com.nightgals.earnings.EarningType;
import com.nightgals.earnings.EarningsService;
import com.nightgals.earnings.PayoutMethod;
import com.nightgals.earnings.PayoutService;
import com.nightgals.earnings.PayoutStatus;
import com.nightgals.earnings.dto.PayoutAccountRequest;
import com.nightgals.media.MediaRepository;
import com.nightgals.media.ContentTier;
import com.nightgals.media.MediaService;
import com.nightgals.media.MediaStatus;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The money. Commission split, attribution, and the guarantee that a balance
 * cannot be paid out twice.
 *
 * <p>Test configuration: 30% commission, zero hold period, minimum payout 1000
 * minor units.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class EarningsTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired MediaService mediaService;
    @Autowired BillingService billingService;
    @Autowired EarningsService earningsService;
    @Autowired PayoutService payoutService;
    @Autowired EarningRepository earningRepository;
    @Autowired MediaRepository mediaRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("A payout moves the balance to reserved, then paid")
    void payoutLifecycle() {
        User creator = earningCreator(2);
        User admin = staff();

        var summary = payoutService.summary(reload(creator));
        assertThat(summary.availableMinor()).isEqualTo(14000);
        assertThat(summary.canRequestPayout()).isTrue();

        payoutService.saveAccount(reload(creator), account());
        var payout = payoutService.requestPayout(reload(creator));
        assertThat(payout.status()).isEqualTo(PayoutStatus.REQUESTED);
        assertThat(payout.amountMinor()).isEqualTo(14000);
        // The creator sees their own number masked.
        assertThat(payout.destination()).doesNotContain("254712345678");

        var afterRequest = payoutService.summary(reload(creator));
        assertThat(afterRequest.availableMinor()).isZero();
        assertThat(afterRequest.reservedMinor()).isEqualTo(14000);

        payoutService.markPaid(payout.id(), "QGR7XK2LMN", admin);

        var afterPaid = payoutService.summary(reload(creator));
        assertThat(afterPaid.reservedMinor()).isZero();
        assertThat(afterPaid.paidMinor()).isEqualTo(14000);
        assertThat(afterPaid.availableMinor()).isZero();
    }

    @Test
    @DisplayName("Only one payout can be in flight at a time")
    void oneOpenPayoutAtATime() {
        User creator = earningCreator(2);
        payoutService.saveAccount(reload(creator), account());
        payoutService.requestPayout(reload(creator));

        assertThatThrownBy(() -> payoutService.requestPayout(reload(creator)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already have a payout being processed");
    }

    @Test
    @DisplayName("Rejecting a payout returns the money to the creator's balance")
    void rejectionReturnsFunds() {
        User creator = earningCreator(2);
        User admin = staff();
        payoutService.saveAccount(reload(creator), account());
        var payout = payoutService.requestPayout(reload(creator));

        payoutService.reject(payout.id(), "Account name does not match ID", admin);

        var summary = payoutService.summary(reload(creator));
        assertThat(summary.availableMinor()).isEqualTo(14000);
        assertThat(summary.reservedMinor()).isZero();
        // And they can ask again.
        assertThat(summary.payoutInProgress()).isFalse();
    }

    @Test
    @DisplayName("Marking paid requires a transaction reference")
    void referenceRequired() {
        User creator = earningCreator(2);
        User admin = staff();
        payoutService.saveAccount(reload(creator), account());
        var payout = payoutService.requestPayout(reload(creator));

        assertThatThrownBy(() -> payoutService.markPaid(payout.id(), "  ", admin))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("transaction reference");
    }

    @Test
    @DisplayName("A payout below the minimum is refused")
    void belowMinimumRefused() {
        User creator = approvedMember();
        payoutService.saveAccount(reload(creator), account());

        assertThatThrownBy(() -> payoutService.requestPayout(reload(creator)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Minimum payout");
    }

    @Test
    @DisplayName("A payout without a payout account is refused")
    void accountRequired() {
        User creator = earningCreator(2);
        assertThatThrownBy(() -> payoutService.requestPayout(reload(creator)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Add a payout account");
    }

    @Test
    @DisplayName("The payout account cannot be changed mid-payout")
    void accountFrozenDuringPayout() {
        User creator = earningCreator(2);
        payoutService.saveAccount(reload(creator), account());
        payoutService.requestPayout(reload(creator));

        assertThatThrownBy(() -> payoutService.saveAccount(reload(creator), account()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot change your payout account");
    }

    @Test
    @DisplayName("Reversing a purchase removes the creator's earning")
    void refundReversesEarning() {
        User creator = approvedMember();
        User viewer = approvedMember();
        var checkout = billingService.unlockMedia(viewer, pricedItem(creator, 10_000L));
        billingService.settle(checkout.purchase().id(), "REF-4");

        assertThat(payoutService.summary(reload(creator)).availableMinor()).isEqualTo(7000);

        earningsService.reverseForPurchase(checkout.purchase().id());

        var summary = payoutService.summary(reload(creator));
        assertThat(summary.availableMinor()).isZero();
        assertThat(earningRepository.findByCreatorIdAndStatus(creator.getId(), EarningStatus.REVERSED))
                .hasSize(1);
    }

    @Test
    @DisplayName("An adjustment credits immediately and requires a reason")
    void adjustments() {
        User creator = approvedMember();

        earningsService.adjust(creator.getId(), 5000, "Goodwill after outage", "KES");
        assertThat(payoutService.summary(reload(creator)).availableMinor()).isEqualTo(5000);

        // Negative adjustments claw back.
        earningsService.adjust(creator.getId(), -2000, "Duplicate credit corrected", "KES");
        assertThat(payoutService.summary(reload(creator)).availableMinor()).isEqualTo(3000);

        assertThatThrownBy(() -> earningsService.adjust(creator.getId(), 100, "  ", "KES"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must say why");
    }

    @Test
    @DisplayName("A creator earns nothing from looking at her own content")
    void ownViewsEarnNothing() {
        User creator = approvedMember();
        publishPhoto(creator);

        // Her own items are never paywalled to her, so there is nothing to buy and
        // nothing to earn - which is what stops a creator farming her own ledger.
        mediaService.listPublic(creator.getId(), reload(creator));

        assertThat(payoutService.summary(reload(creator)).lifetimeMinor()).isZero();
    }

    // ------------------------------------------------------------- helpers

    private User register() {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private User approvedMember() {
        User user = register();
        profileService.createOrUpdate(user, new ProfileRequest(
                null, "Out most weekends", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Nairobi", "Kenya", null, null));
        User managed = reload(user);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        return userRepository.save(managed);
    }

    private User staff() {
        User user = register();
        User managed = reload(user);
        managed.setRole(com.nightgals.user.Role.ADMIN);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        return userRepository.save(managed);
    }

    /** A creator with {@code unlocks} settled unlocks, i.e. 7000 minor units each. */
    private User earningCreator(int unlocks) {
        User creator = approvedMember();
        for (int i = 0; i < unlocks; i++) {
            User viewer = approvedMember();
            var checkout = billingService.unlockMedia(viewer, pricedItem(creator, 10_000L));
            billingService.settle(checkout.purchase().id(), "REF-" + UUID.randomUUID());
        }
        return creator;
    }

    /**
     * Gives a creator something worth paying for: a free profile picture plus one
     * exclusive photo. Subscription revenue is only attributed when a subscriber
     * consumes *paid* content, so a creator with nothing exclusive earns nothing.
     */
    private MockMultipartFile photo() {
        return new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    private void publishPhoto(User user) {
        mediaService.upload(reload(user), MediaType.PHOTO,
                new MockMultipartFile("file", "free.jpg", "image/jpeg", new byte[]{1, 2, 3}), null,
                ContentTier.FREE, null);
        mediaService.upload(reload(user), MediaType.PHOTO,
                new MockMultipartFile("file", "paid.jpg", "image/jpeg", new byte[]{4, 5, 6}), null,
                ContentTier.EXCLUSIVE, null);
    }

    private PayoutAccountRequest account() {
        return new PayoutAccountRequest(PayoutMethod.MPESA, "254712345678", "Amina Wanjiru Kamau", null);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    /**
     * A priced item on this creator's profile.
     *
     * <p>Publishes twice: the first photo is forced FREE because it becomes the
     * profile picture, so the second is the one that can actually be sold.
     */
    private java.util.UUID pricedItem(User creator, long priceMinor) {
        mediaService.upload(reload(creator), MediaType.PHOTO, photo(),
                null, com.nightgals.media.ContentTier.FREE, null);
        return mediaService.upload(reload(creator), MediaType.PHOTO, photo(),
                null, com.nightgals.media.ContentTier.EXCLUSIVE, priceMinor).id();
    }
}
