package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
import com.nightgals.billing.EntitlementService;
import com.nightgals.billing.PurchaseStatus;
import com.nightgals.common.ApiException;
import com.nightgals.discovery.FeedService;
import com.nightgals.discovery.dto.MemberCardResponse;
import com.nightgals.media.ContentTier;
import com.nightgals.media.MediaAsset;
import com.nightgals.media.MediaRepository;
import com.nightgals.media.MediaService;
import com.nightgals.media.MediaStatus;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Free to look, paid to watch - and the unit of payment is the item.
 *
 * <p>What a creator marks FREE is the shop window, open to anyone signed in or
 * not. What she marks EXCLUSIVE is sold one piece at a time, at the price she put
 * on that piece. Buying one thing buys that thing.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class PaywallTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired MediaService mediaService;
    @Autowired BillingService billingService;
    @Autowired EntitlementService entitlementService;
    @Autowired MediaRepository mediaRepository;
    @Autowired UserRepository userRepository;
    @Autowired FeedService feedService;

    // ---------------------------------------------------------------- the feed

    @Test
    @DisplayName("The feed lists creators, with and without a city filter")
    void feedListsMembers() {
        User creator = approvedCreator();
        publish(creator, ContentTier.EXCLUSIVE, 9_000L);
        publish(creator, ContentTier.EXCLUSIVE, 3_000L);
        User viewer = viewer();

        var card = cardFor(viewer, creator);
        assertThat(card.username()).isNotBlank();
        assertThat(card.freePhotoUrls()).hasSize(1);          // the profile picture
        assertThat(card.lockedPhotoCount()).isEqualTo(2);
        // "From X" is the cheapest locked thing, not the first one found.
        assertThat(card.fromPriceMinor()).isEqualTo(3_000L);

        assertThat(feedService.feed(viewer, "nairobi", null, null, PageRequest.of(0, 50)).content())
                .anySatisfy(c -> assertThat(c.userId()).isEqualTo(creator.getId()));
        assertThat(feedService.feed(viewer, "Kisumu", null, null, PageRequest.of(0, 50)).content())
                .noneSatisfy(c -> assertThat(c.userId()).isEqualTo(creator.getId()));
    }

    @Test
    @DisplayName("The feed never shows the caller their own card")
    void feedExcludesSelf() {
        User viewer = approvedCreator();
        assertThat(feedService.feed(viewer, null, null, null, PageRequest.of(0, 50)).content())
                .noneSatisfy(c -> assertThat(c.userId()).isEqualTo(viewer.getId()));
    }

    @Test
    @DisplayName("Buying one item changes only that item's count on the card")
    void cardReflectsWhatIsOwned() {
        User creator = approvedCreator();
        UUID first = publish(creator, ContentTier.EXCLUSIVE, 3_000L);
        publish(creator, ContentTier.EXCLUSIVE, 3_000L);
        User viewer = viewer();

        assertThat(cardFor(viewer, creator).lockedPhotoCount()).isEqualTo(2);

        buy(viewer, first);

        var after = cardFor(reload(viewer), creator);
        assertThat(after.lockedPhotoCount()).isEqualTo(1);
        assertThat(after.freePhotoUrls()).hasSize(2);         // cover + the one bought
    }

    // ---------------------------------------------------------------- browsing

    @Test
    @DisplayName("A viewer needs no verification to browse or to pay")
    void viewersDoNotNeedVerification() {
        User creator = approvedCreator();
        UUID item = publish(creator, ContentTier.EXCLUSIVE, 3_000L);
        User viewer = viewer();   // registered, never did KYC

        assertThat(profileService.getPublic(creator.getId(), viewer).username()).isNotBlank();
        assertThat(feedService.feed(viewer, null, null, null, PageRequest.of(0, 50)).content())
                .anySatisfy(c -> assertThat(c.userId()).isEqualTo(creator.getId()));

        buy(viewer, item);
        assertThat(entitlementService.canView(reload(viewer), asset(item))).isTrue();
    }

    @Test
    @DisplayName("An anonymous visitor sees the shop window and nothing past it")
    void anonymousSeesShopWindow() {
        User creator = approvedCreator();
        UUID paid = publish(creator, ContentTier.EXCLUSIVE, 3_000L);
        UUID cover = mediaService.listOwn(creator.getId()).getFirst().id();

        var card = cardFor(null, creator);
        assertThat(card.freePhotoUrls()).hasSize(1);
        assertThat(card.lockedPhotoCount()).isEqualTo(1);

        var profile = profileService.getPublic(creator.getId(), null);
        assertThat(profile.username()).isNotBlank();
        // No identifying detail leaks to the open internet.
        assertThat(profile.displayName()).isNull();
        assertThat(profile.dateOfBirth()).isNull();

        // The free item really is fetchable without a token...
        assertThat(mediaService.download(cover, null)).isNotNull();
        // ...and nothing paid is.
        assertThatThrownBy(() -> mediaService.download(paid, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Sign in");
    }

    @Test
    @DisplayName("An anonymous caller is entitled to nothing paid")
    void anonymousNeverEntitled() {
        User creator = approvedCreator();
        UUID item = publish(creator, ContentTier.EXCLUSIVE, 3_000L);

        assertThat(entitlementService.canView(null, asset(item))).isFalse();
        assertThat(entitlementService.viewableAmong(null, java.util.List.of(asset(item)))).isEmpty();
    }

    // ---------------------------------------------------------------- tiers

    @Test
    @DisplayName("A KYC-approved creator's uploads are live immediately, with no review step")
    void uploadsPublishImmediately() {
        User creator = approvedCreator();
        var uploaded = mediaService.upload(reload(creator), MediaType.PHOTO, photo(),
                "straight up", ContentTier.FREE, null);

        assertThat(uploaded.status()).isEqualTo(MediaStatus.APPROVED);
        assertThat(uploaded.locked()).isFalse();
    }

    @Test
    @DisplayName("The first photo becomes the profile picture and is forced free")
    void firstPhotoIsTheCover() {
        User creator = freshCreator();

        var first = mediaService.upload(reload(creator), MediaType.PHOTO, photo(),
                null, ContentTier.EXCLUSIVE, 9_000L);

        assertThat(first.tier()).isEqualTo(ContentTier.FREE);
        assertThat(first.primary()).isTrue();
        // A free item is the shop window and is never priced.
        assertThat(asset(first.id()).getUnlockPriceMinor()).isNull();
    }

    @Test
    @DisplayName("A creator can move an item between free and exclusive after posting")
    void tierCanBeChangedLater() {
        User creator = approvedCreator();
        UUID item = publish(creator, ContentTier.EXCLUSIVE, 3_000L);

        assertThat(freeCount(creator)).isEqualTo(1);

        mediaService.update(reload(creator), item,
                new MediaUpdateRequest(null, null, ContentTier.FREE, null, null));
        assertThat(freeCount(creator)).isEqualTo(2);

        mediaService.update(reload(creator), item,
                new MediaUpdateRequest(null, null, ContentTier.EXCLUSIVE, null, null));
        assertThat(freeCount(creator)).isEqualTo(1);
    }

    @Test
    @DisplayName("The profile picture cannot be hidden behind the paywall")
    void primaryPhotoStaysFree() {
        User creator = approvedCreator();
        UUID cover = mediaService.listOwn(creator.getId()).getFirst().id();
        UUID other = publish(creator, ContentTier.EXCLUSIVE, 3_000L);

        assertThatThrownBy(() -> mediaService.update(reload(creator), cover,
                new MediaUpdateRequest(null, null, ContentTier.EXCLUSIVE, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("profile picture has to stay free");

        // Making a different photo primary forces it free.
        var promoted = mediaService.update(reload(creator), other,
                new MediaUpdateRequest(null, null, null, true, null));
        assertThat(promoted.tier()).isEqualTo(ContentTier.FREE);
        assertThat(promoted.primary()).isTrue();
    }

    // ---------------------------------------------------------------- files

    @Test
    @DisplayName("Fetching a locked file is 401 anonymous, 402 signed in")
    void downloadIsGated() {
        User creator = approvedCreator();
        UUID item = publish(creator, ContentTier.EXCLUSIVE, 3_000L);
        User viewer = viewer();

        // Signing in is the next step for a visitor; paying is the next step for
        // somebody who already has an account.
        assertThatThrownBy(() -> mediaService.download(item, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Sign in");

        assertThatThrownBy(() -> mediaService.download(item, viewer))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unlock this item");

        buy(viewer, item);
        assertThat(mediaService.download(item, reload(viewer))).isNotNull();
    }

    @Test
    @DisplayName("A creator never pays to see her own content")
    void ownContentIsFree() {
        User creator = approvedCreator();
        UUID item = publish(creator, ContentTier.EXCLUSIVE, 3_000L);

        assertThat(entitlementService.canView(reload(creator), asset(item))).isTrue();
        assertThat(mediaService.download(item, reload(creator))).isNotNull();
        assertThatThrownBy(() -> billingService.unlockMedia(reload(creator), item))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already yours");
    }

    // ---------------------------------------------------------------- buying

    @Test
    @DisplayName("Buying one item does not open her others")
    void purchaseIsScopedToTheItem() {
        User creator = approvedCreator();
        UUID bought = publish(creator, ContentTier.EXCLUSIVE, 3_000L);
        UUID other = publish(creator, ContentTier.EXCLUSIVE, 3_000L);
        User viewer = viewer();

        buy(viewer, bought);

        assertThat(entitlementService.canView(reload(viewer), asset(bought))).isTrue();
        // The whole point of per-item pricing.
        assertThat(entitlementService.canView(reload(viewer), asset(other))).isFalse();
    }

    @Test
    @DisplayName("Access is granted when the purchase settles, not when it is created")
    void pendingPurchaseGrantsNothing() {
        User creator = approvedCreator();
        UUID item = publish(creator, ContentTier.EXCLUSIVE, 3_000L);
        User viewer = viewer();

        var checkout = billingService.unlockMedia(viewer, item);

        assertThat(checkout.purchase().status()).isEqualTo(PurchaseStatus.PENDING);
        assertThat(entitlementService.canView(reload(viewer), asset(item))).isFalse();
    }

    @Test
    @DisplayName("Settling twice does not grant twice")
    void settlementIsIdempotent() {
        User creator = approvedCreator();
        UUID item = publish(creator, ContentTier.EXCLUSIVE, 3_000L);
        User viewer = viewer();

        var checkout = billingService.unlockMedia(viewer, item);
        billingService.settle(checkout.purchase().id(), "REF");
        var second = billingService.settle(checkout.purchase().id(), "REF");

        assertThat(second.status()).isEqualTo(PurchaseStatus.COMPLETED);
        assertThat(billingService.entitlements(reload(viewer)).unlockedItems()).isEqualTo(1);
    }

    @Test
    @DisplayName("Buying the same item twice is refused")
    void doubleBuyRefused() {
        User creator = approvedCreator();
        UUID item = publish(creator, ContentTier.EXCLUSIVE, 3_000L);
        User viewer = viewer();
        buy(viewer, item);

        assertThatThrownBy(() -> billingService.unlockMedia(reload(viewer), item))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already have this");
    }

    @Test
    @DisplayName("A free item cannot be bought")
    void freeItemsAreNotForSale() {
        User creator = approvedCreator();
        UUID cover = mediaService.listOwn(creator.getId()).getFirst().id();

        assertThatThrownBy(() -> billingService.unlockMedia(viewer(), cover))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("free to watch");
    }

    @Test
    @DisplayName("The purchase count is of items, not of creators")
    void entitlementsCountItems() {
        User creator = approvedCreator();
        User viewer = viewer();
        buy(viewer, publish(creator, ContentTier.EXCLUSIVE, 3_000L));
        buy(reload(viewer), publish(creator, ContentTier.EXCLUSIVE, 3_000L));

        // Two items from one creator is two, not one.
        assertThat(billingService.entitlements(reload(viewer)).unlockedItems()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- moderation

    @Test
    @DisplayName("A moderator can take published media down, and put it back")
    void takedownAndRestore() {
        User creator = approvedCreator();
        UUID item = publish(creator, ContentTier.EXCLUSIVE, 3_000L);
        assertThat(mediaService.listPublic(creator.getId(), null)).hasSize(2);

        var removed = mediaService.takeDown(item, "Contains someone else's face");
        assertThat(removed.status()).isEqualTo(MediaStatus.REJECTED);
        assertThat(mediaService.listPublic(creator.getId(), null)).hasSize(1);
        // The creator still sees it, with the reason.
        assertThat(mediaService.listOwn(creator.getId())).hasSize(2);

        mediaService.restore(item);
        assertThat(mediaService.listPublic(creator.getId(), null)).hasSize(2);
    }

    @Test
    @DisplayName("A takedown must say why")
    void takedownNeedsReason() {
        User creator = approvedCreator();
        UUID item = publish(creator, ContentTier.EXCLUSIVE, 3_000L);

        assertThatThrownBy(() -> mediaService.takeDown(item, "   "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Say why");
    }

    // ---------------------------------------------------------------- helpers

    private void buy(User viewer, UUID mediaId) {
        var checkout = billingService.unlockMedia(reload(viewer), mediaId);
        billingService.settle(checkout.purchase().id(), null);
    }

    private UUID publish(User creator, ContentTier tier, Long priceMinor) {
        return mediaService.upload(reload(creator), MediaType.PHOTO, photo(),
                null, tier, priceMinor).id();
    }

    private long freeCount(User creator) {
        return mediaService.listPublic(creator.getId(), null).stream()
                .filter(m -> !m.locked()).count();
    }

    private MemberCardResponse cardFor(User viewer, User creator) {
        return feedService.feed(viewer, null, null, null, PageRequest.of(0, 50)).content().stream()
                .filter(c -> c.userId().equals(creator.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("creator missing from the feed"));
    }

    private MediaAsset asset(UUID id) {
        return mediaRepository.findById(id).orElseThrow();
    }

    private MockMultipartFile photo() {
        return new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[] {1, 2, 3, 4});
    }

    /** Verified and discoverable, but with nothing posted yet. */
    private User freshCreator() {
        String email = "creator-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();

        profileService.createOrUpdate(user, new ProfileRequest(
                null, "Out most weekends", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Nairobi", "Kenya", null, null));

        User managed = reload(user);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        return userRepository.saveAndFlush(managed);
    }

    /** As above, but the forced-free profile picture is already used up. */
    private User approvedCreator() {
        User creator = freshCreator();
        mediaService.upload(creator, MediaType.PHOTO, photo(), null, ContentTier.FREE, null);
        return reload(creator);
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
