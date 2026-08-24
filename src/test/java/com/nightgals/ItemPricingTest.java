package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
import com.nightgals.billing.EntitlementService;
import com.nightgals.common.ApiException;
import com.nightgals.media.ContentTier;
import com.nightgals.media.MediaAsset;
import com.nightgals.media.MediaRepository;
import com.nightgals.media.MediaService;
import com.nightgals.media.MediaType;
import com.nightgals.media.dto.MediaUpdateRequest;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Everything is priced per item, by its creator.
 *
 * <p>"Users can set their own unlock price for every premium video they upload."
 * Buying one video buys that video - not the creator, and not her other videos.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class ItemPricingTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired MediaService mediaService;
    @Autowired BillingService billingService;
    @Autowired EntitlementService entitlementService;
    @Autowired MediaRepository mediaRepository;
    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("Each item carries the price its creator put on it")
    void perItemPrices() {
        User creator = approvedCreator();
        UUID cheap = publish(creator, 1_500L);
        UUID dear = publish(creator, 40_000L);
        User viewer = viewer();

        assertThat(billingService.unlockMedia(viewer, cheap).purchase().amountMinor())
                .isEqualTo(1_500L);
        assertThat(billingService.unlockMedia(reload(viewer), dear).purchase().amountMinor())
                .isEqualTo(40_000L);
    }

    @Test
    @DisplayName("An item with no price of its own sells at the platform default")
    void defaultPrice() {
        User creator = approvedCreator();
        UUID unpriced = publish(creator, null);
        User viewer = viewer();

        // default-price-minor is 2000 in the test configuration.
        assertThat(billingService.unlockMedia(viewer, unpriced).purchase().amountMinor())
                .isEqualTo(2_000L);
    }

    @Test
    @DisplayName("Buying one video buys that video and nothing else of hers")
    void purchaseDoesNotSpread() {
        User creator = approvedCreator();
        UUID bought = publish(creator, 3_000L);
        UUID other = publish(creator, 3_000L);
        User viewer = viewer();

        billingService.settle(billingService.unlockMedia(viewer, bought).purchase().id(), null);

        assertThat(entitlementService.canView(reload(viewer), media(bought))).isTrue();
        // This is the whole point of per-item pricing.
        assertThat(entitlementService.canView(reload(viewer), media(other))).isFalse();
    }

    @Test
    @DisplayName("A locked tile still shows what it costs")
    void lockedTilesCarryTheirPrice() {
        User creator = approvedCreator();
        publish(creator, 7_500L);
        User viewer = viewer();

        var gallery = mediaService.listPublic(creator.getId(), viewer);
        var locked = gallery.stream().filter(m -> m.locked()).findFirst().orElseThrow();

        assertThat(locked.url()).isNull();
        // A blurred placeholder with no price is not something anybody buys.
        assertThat(locked.priceMinor()).isEqualTo(7_500L);
        assertThat(locked.currency()).isEqualTo("XAF");
    }

    @Test
    @DisplayName("XAF has no minor unit, so 7500 is seven and a half thousand francs")
    void zeroDecimalCurrency() {
        User creator = approvedCreator();
        publish(creator, 7_500L);
        User viewer = viewer();

        var gallery = mediaService.listPublic(creator.getId(), viewer);
        var locked = gallery.stream().filter(m -> m.locked()).findFirst().orElseThrow();

        // Dividing by 100 the way a euro would be divided gives "75.00" and
        // understates every price on the site by two orders of magnitude.
        assertThat(locked.priceDisplay()).isEqualTo("7500").isNotEqualTo("75.00");
    }

    @Test
    @DisplayName("A free item cannot be bought, and is never priced")
    void freeItemsAreNotForSale() {
        User creator = approvedCreator();
        UUID free = mediaService.upload(reload(creator), MediaType.PHOTO, photo(),
                null, ContentTier.FREE, 5_000L).id();
        User viewer = viewer();

        assertThat(media(free).getUnlockPriceMinor()).isNull();
        assertThatThrownBy(() -> billingService.unlockMedia(viewer, free))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("free to watch");
    }

    @Test
    @DisplayName("Prices outside the platform's bounds are refused")
    void boundsAreEnforced() {
        User creator = approvedCreator();

        assertThatThrownBy(() -> publish(creator, 10L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("lowest you can charge");
        assertThatThrownBy(() -> publish(creator, 9_000_000L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("most you can charge");
    }

    @Test
    @DisplayName("A creator can reprice an item she already posted")
    void repricing() {
        User creator = approvedCreator();
        UUID item = publish(creator, 3_000L);

        mediaService.update(reload(creator), item,
                new MediaUpdateRequest(null, null, null, null, 12_000L));

        assertThat(media(item).getUnlockPriceMinor()).isEqualTo(12_000L);
    }

    @Test
    @DisplayName("Changing a price does not change what a pending checkout already quoted")
    void quotedPriceIsHeld() {
        User creator = approvedCreator();
        UUID item = publish(creator, 3_000L);
        User viewer = viewer();

        var first = billingService.unlockMedia(viewer, item);
        mediaService.update(reload(creator), item,
                new MediaUpdateRequest(null, null, null, null, 50_000L));

        var again = billingService.unlockMedia(reload(viewer), item);
        assertThat(again.purchase().id()).isEqualTo(first.purchase().id());
        assertThat(again.purchase().amountMinor()).isEqualTo(3_000L);
    }

    // ------------------------------------------------------------- helpers

    private UUID publish(User creator, Long priceMinor) {
        return mediaService.upload(reload(creator), MediaType.PHOTO, photo(),
                null, ContentTier.EXCLUSIVE, priceMinor).id();
    }

    private MediaAsset media(UUID id) {
        return mediaRepository.findById(id).orElseThrow();
    }

    private MockMultipartFile photo() {
        return new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    /**
     * A verified creator who has already used up the forced-free profile picture,
     * so anything published afterwards is genuinely exclusive.
     */
    private User approvedCreator() {
        String email = "creator-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();

        profileService.createOrUpdate(user, new ProfileRequest(
                null, "Here for the weekend", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Douala", "Cameroon", null, null));

        User managed = reload(user);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        User saved = userRepository.saveAndFlush(managed);

        mediaService.upload(saved, MediaType.PHOTO, photo(), null, ContentTier.FREE, null);
        return reload(saved);
    }

    private User viewer() {
        String email = "viewer-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.VIEWER, null), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
