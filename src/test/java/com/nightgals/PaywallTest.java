package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
import com.nightgals.billing.EntitlementService;
import com.nightgals.billing.PurchaseStatus;
import com.nightgals.common.ApiException;
import com.nightgals.discovery.FeedService;
import com.nightgals.media.MediaRepository;
import com.nightgals.media.ContentTier;
import com.nightgals.media.MediaService;
import com.nightgals.media.dto.MediaUpdateRequest;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Free to scroll, paid to see the person. Also covers the rule that only
 * verified members may browse at all.
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

    @Test
    @DisplayName("The feed lists verified members, with and without a city filter")
    void feedListsMembers() {
        User creator = approvedMember();
        publishPhotos(creator, 3);
        User viewer = approvedMember();

        // No filter: the null city parameter must not break the query.
        var unfiltered = feedService.feed(viewer, null, PageRequest.of(0, 20));
        assertThat(unfiltered.content()).isNotEmpty();
        var card = unfiltered.content().stream()
                .filter(c -> c.userId().equals(creator.getId())).findFirst().orElseThrow();

        // free-preview-photos is 1 in the test configuration
        assertThat(card.freePhotoUrls()).hasSize(1);
        assertThat(card.lockedPhotoCount()).isEqualTo(2);
        assertThat(card.unlocked()).isFalse();
        assertThat(card.username()).isNotBlank();

        // Matching filter, case-insensitively.
        assertThat(feedService.feed(viewer, "nairobi", PageRequest.of(0, 20)).content())
                .anySatisfy(c -> assertThat(c.userId()).isEqualTo(creator.getId()));
        // Non-matching filter.
        assertThat(feedService.feed(viewer, "Kisumu", PageRequest.of(0, 20)).content())
                .noneSatisfy(c -> assertThat(c.userId()).isEqualTo(creator.getId()));
    }

    @Test
    @DisplayName("The feed never shows the caller their own card")
    void feedExcludesSelf() {
        User viewer = approvedMember();
        assertThat(feedService.feed(viewer, null, PageRequest.of(0, 20)).content())
                .noneSatisfy(c -> assertThat(c.userId()).isEqualTo(viewer.getId()));
    }

    @Test
    @DisplayName("Unlocking flips the card to fully visible")
    void unlockRevealsCard() {
        User creator = approvedMember();
        publishPhotos(creator, 3);
        User viewer = approvedMember();

        var checkout = billingService.unlockProfile(viewer, creator.getId());
        billingService.settle(checkout.purchase().id(), "TEST-FEED-1");

        var card = feedService.feed(reload(viewer), null, PageRequest.of(0, 20)).content().stream()
                .filter(c -> c.userId().equals(creator.getId())).findFirst().orElseThrow();

        assertThat(card.unlocked()).isTrue();
        assertThat(card.freePhotoUrls()).hasSize(3);
        assertThat(card.lockedPhotoCount()).isZero();
    }

    @Test
    @DisplayName("A viewer needs no verification to browse - KYC is a creator requirement")
    void viewersDoNotNeedVerification() {
        User creator = approvedMember();
        publishPhotos(creator, 3);
        User viewer = register();   // registered, never did KYC

        assertThat(profileService.getPublic(creator.getId(), viewer).username()).isNotBlank();
        assertThat(feedService.feed(viewer, null, PageRequest.of(0, 20)).content())
                .anySatisfy(c -> assertThat(c.userId()).isEqualTo(creator.getId()));

        // ...and they can pay, still without ever verifying.
        var checkout = billingService.unlockProfile(viewer, creator.getId());
        billingService.settle(checkout.purchase().id(), "VIEWER-NO-KYC");
        assertThat(mediaService.listPublic(creator.getId(), reload(viewer)))
                .allSatisfy(m -> assertThat(m.locked()).isFalse());
    }

    @Test
    @DisplayName("An anonymous visitor sees the shop window: cards, profile, one preview photo")
    void anonymousSeesShopWindow() {
        User creator = approvedMember();
        var assets = publishPhotos(creator, 3);
        publishVideo(creator);

        // null viewer == anonymous
        var feed = feedService.feed(null, null, PageRequest.of(0, 20));
        var card = feed.content().stream()
                .filter(c -> c.userId().equals(creator.getId())).findFirst().orElseThrow();
        assertThat(card.freePhotoUrls()).hasSize(1);
        assertThat(card.lockedPhotoCount()).isEqualTo(2);
        assertThat(card.lockedVideoCount()).isEqualTo(1);
        assertThat(card.unlocked()).isFalse();

        var profile = profileService.getPublic(creator.getId(), null);
        assertThat(profile.username()).isNotBlank();
        // Still no identifying detail leaks to the open internet.
        assertThat(profile.displayName()).isNull();
        assertThat(profile.dateOfBirth()).isNull();

        var gallery = mediaService.listPublic(creator.getId(), null);
        assertThat(gallery.stream().filter(m -> !m.locked()).toList()).hasSize(1);
        assertThat(gallery.stream().filter(m -> m.locked()).toList()).hasSize(3);

        // The one free preview really is fetchable without a token...
        assertThat(mediaService.download(assets.getFirst(), null)).isNotNull();
        // ...and nothing past it is.
        assertThatThrownBy(() -> mediaService.download(assets.get(1), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Sign in");
    }

    @Test
    @DisplayName("Anonymous callers hold no entitlements even with the paywall switched off")
    void anonymousNeverEntitled() {
        User creator = approvedMember();
        assertThat(entitlementService.canViewPremium(null, creator.getId())).isFalse();
        assertThat(entitlementService.unlockedAmong(null, java.util.List.of(creator.getId()))).isEmpty();
    }

    @Test
    @DisplayName("A KYC-approved creator's uploads are live immediately, with no review step")
    void uploadsPublishImmediately() {
        User creator = approvedMember();
        var uploaded = mediaService.upload(reload(creator), MediaType.PHOTO,
                file("p.jpg", "image/jpeg"), "straight up", ContentTier.FREE);

        assertThat(uploaded.status()).isEqualTo(MediaStatus.APPROVED);
        assertThat(uploaded.locked()).isFalse();
        // Visible to an anonymous passer-by the moment it lands.
        assertThat(mediaService.listPublic(creator.getId(), null)).hasSize(1);
    }

    @Test
    @DisplayName("A creator chooses per item what is free and what is exclusive")
    void creatorChoosesTierPerItem() {
        User creator = approvedMember();
        // First photo is the profile picture and is forced FREE.
        var first = mediaService.upload(reload(creator), MediaType.PHOTO,
                file("a.jpg", "image/jpeg"), null, ContentTier.EXCLUSIVE);
        assertThat(first.tier()).isEqualTo(ContentTier.FREE);
        assertThat(first.primary()).isTrue();

        // A second one honours what was asked for.
        var teaser = mediaService.upload(reload(creator), MediaType.PHOTO,
                file("b.jpg", "image/jpeg"), null, ContentTier.FREE);
        var paid = mediaService.upload(reload(creator), MediaType.PHOTO,
                file("c.jpg", "image/jpeg"), null, ContentTier.EXCLUSIVE);
        var freeClip = mediaService.upload(reload(creator), MediaType.VIDEO,
                file("v.mp4", "video/mp4"), null, ContentTier.FREE);
        assertThat(teaser.tier()).isEqualTo(ContentTier.FREE);
        assertThat(paid.tier()).isEqualTo(ContentTier.EXCLUSIVE);

        // Anonymous: two free photos and the free clip play, the exclusive one does not.
        var gallery = mediaService.listPublic(creator.getId(), null);
        assertThat(gallery.stream().filter(m -> !m.locked()).toList()).hasSize(3);
        assertThat(gallery.stream().filter(m -> m.locked()).toList()).hasSize(1);
        assertThat(mediaService.download(teaser.id(), null)).isNotNull();
        assertThat(mediaService.download(freeClip.id(), null)).isNotNull();
        assertThatThrownBy(() -> mediaService.download(paid.id(), null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("A creator can move an item between free and exclusive after posting")
    void tierCanBeChangedLater() {
        User creator = approvedMember();
        var ids = publishPhotos(creator, 2);
        UUID second = ids.get(1);

        assertThat(mediaService.listPublic(creator.getId(), null).stream()
                .filter(m -> !m.locked()).toList()).hasSize(1);

        // Promote the exclusive one into the shop window.
        var moved = mediaService.update(reload(creator), second,
                new MediaUpdateRequest(null, null, ContentTier.FREE, null));
        assertThat(moved.tier()).isEqualTo(ContentTier.FREE);
        assertThat(mediaService.listPublic(creator.getId(), null).stream()
                .filter(m -> !m.locked()).toList()).hasSize(2);

        // And back again.
        mediaService.update(reload(creator), second,
                new MediaUpdateRequest(null, null, ContentTier.EXCLUSIVE, null));
        assertThat(mediaService.listPublic(creator.getId(), null).stream()
                .filter(m -> m.locked()).toList()).hasSize(1);
    }

    @Test
    @DisplayName("The profile picture cannot be hidden behind the paywall")
    void primaryPhotoStaysFree() {
        User creator = approvedMember();
        var ids = publishPhotos(creator, 2);

        assertThatThrownBy(() -> mediaService.update(reload(creator), ids.getFirst(),
                new MediaUpdateRequest(null, null, ContentTier.EXCLUSIVE, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("profile picture has to stay free");

        // Making a different photo primary forces it free.
        var promoted = mediaService.update(reload(creator), ids.get(1),
                new MediaUpdateRequest(null, null, null, true));
        assertThat(promoted.tier()).isEqualTo(ContentTier.FREE);
        assertThat(promoted.primary()).isTrue();
    }

    @Test
    @DisplayName("A moderator can take published media down, and put it back")
    void takedownAndRestore() {
        User creator = approvedMember();
        var ids = publishPhotos(creator, 2);
        assertThat(mediaService.listPublic(creator.getId(), null)).hasSize(2);

        var removed = mediaService.takeDown(ids.getFirst(), "Contains someone else's face");
        assertThat(removed.status()).isEqualTo(MediaStatus.REJECTED);
        assertThat(removed.rejectionReason()).isEqualTo("Contains someone else's face");
        assertThat(mediaService.listPublic(creator.getId(), null)).hasSize(1);

        // The creator still sees it, with the reason.
        assertThat(mediaService.listOwn(creator.getId())).hasSize(2);

        mediaService.restore(ids.getFirst());
        assertThat(mediaService.listPublic(creator.getId(), null)).hasSize(2);
    }

    @Test
    @DisplayName("A takedown must say why")
    void takedownNeedsReason() {
        User creator = approvedMember();
        var ids = publishPhotos(creator, 1);

        assertThatThrownBy(() -> mediaService.takeDown(ids.getFirst(), "   "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Say why");
    }

    @Test
    @DisplayName("A verified member sees the free preview and locked placeholders")
    void previewIsFreeRestIsLocked() {
        User creator = approvedMember();
        publishPhotos(creator, 3);
        publishVideo(creator);

        User viewer = approvedMember();
        var gallery = mediaService.listPublic(creator.getId(), viewer);

        assertThat(gallery).hasSize(4);
        // free-preview-photos is 1 in the test configuration
        assertThat(gallery.stream().filter(m -> !m.locked()).toList()).hasSize(1);
        assertThat(gallery.stream().filter(m -> !m.locked()).toList().getFirst().url()).isNotNull();
        assertThat(gallery.stream().filter(m -> m.locked()).toList()).hasSize(3);
        assertThat(gallery.stream().filter(m -> m.locked()).toList())
                .allSatisfy(m -> assertThat(m.url()).isNull());
    }

    @Test
    @DisplayName("Fetching a locked file is refused with 402 until it is unlocked")
    void lockedFileRequiresPayment() {
        User creator = approvedMember();
        var assets = publishPhotos(creator, 2);
        UUID lockedId = assets.get(1);

        User viewer = approvedMember();
        assertThatThrownBy(() -> mediaService.download(lockedId, viewer))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unlock this member");

        // Paying settles the purchase and opens it.
        var checkout = billingService.unlockProfile(viewer, creator.getId());
        assertThat(checkout.purchase().status()).isEqualTo(PurchaseStatus.PENDING);
        billingService.settle(checkout.purchase().id(), "TEST-REF-1");

        assertThat(mediaService.download(lockedId, reload(viewer))).isNotNull();
    }

    @Test
    @DisplayName("Access is granted only when the purchase settles, not when it is created")
    void pendingPurchaseGrantsNothing() {
        User creator = approvedMember();
        User viewer = approvedMember();

        billingService.unlockProfile(viewer, creator.getId());
        assertThat(entitlementService.canViewPremium(reload(viewer), creator.getId())).isFalse();
    }

    @Test
    @DisplayName("A subscription unlocks everybody")
    void subscriptionUnlocksEveryone() {
        User first = approvedMember();
        User second = approvedMember();
        publishPhotos(first, 2);
        publishPhotos(second, 2);

        User viewer = approvedMember();
        assertThat(entitlementService.canViewPremium(viewer, first.getId())).isFalse();

        var checkout = billingService.subscribe(viewer, "MONTHLY");
        billingService.settle(checkout.purchase().id(), "TEST-SUB-1");

        User subscribed = reload(viewer);
        assertThat(entitlementService.canViewPremium(subscribed, first.getId())).isTrue();
        assertThat(entitlementService.canViewPremium(subscribed, second.getId())).isTrue();
        assertThat(mediaService.listPublic(first.getId(), subscribed))
                .allSatisfy(m -> assertThat(m.locked()).isFalse());
    }

    @Test
    @DisplayName("Settling twice does not grant twice")
    void settlementIsIdempotent() {
        User creator = approvedMember();
        User viewer = approvedMember();

        var checkout = billingService.unlockProfile(viewer, creator.getId());
        billingService.settle(checkout.purchase().id(), "TEST-REF-2");
        var second = billingService.settle(checkout.purchase().id(), "TEST-REF-2");

        assertThat(second.status()).isEqualTo(PurchaseStatus.COMPLETED);
        assertThat(billingService.entitlements(reload(viewer)).unlockedMembers()).hasSize(1);
    }

    @Test
    @DisplayName("Unlocking a member you already have access to is refused")
    void doubleUnlockRefused() {
        User creator = approvedMember();
        User viewer = approvedMember();

        var checkout = billingService.unlockProfile(viewer, creator.getId());
        billingService.settle(checkout.purchase().id(), "TEST-REF-3");

        User unlocked = reload(viewer);
        assertThatThrownBy(() -> billingService.unlockProfile(unlocked, creator.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already have access");
    }

    @Test
    @DisplayName("A member never pays to see their own content")
    void ownContentIsFree() {
        User creator = approvedMember();
        var assets = publishPhotos(creator, 3);

        assertThat(entitlementService.canViewPremium(creator, creator.getId())).isTrue();
        assertThat(mediaService.download(assets.get(2), creator)).isNotNull();
        assertThatThrownBy(() -> billingService.unlockProfile(creator, creator.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("your own profile");
    }

    @Test
    @DisplayName("An unknown plan code is refused")
    void unknownPlanRefused() {
        User viewer = approvedMember();
        assertThatThrownBy(() -> billingService.subscribe(viewer, "LIFETIME"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No such plan");
    }

    // ------------------------------------------------------------- helpers

    private User register() {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR));
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private User approvedMember() {
        User user = register();
        profileService.createOrUpdate(user, new ProfileRequest(
                null, "Out most weekends", LocalDate.of(1996, 5, 5),
                Gender.PREFER_NOT_TO_SAY, "Nairobi", "Kenya", null, null));
        User managed = reload(user);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        return userRepository.save(managed);
    }

    /**
     * Publishes photos and returns their ids in order. The first is the profile
     * picture and is therefore FREE; the rest are EXCLUSIVE.
     */
    private List<UUID> publishPhotos(User user, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> mediaService.upload(reload(user), MediaType.PHOTO,
                        file("p.jpg", "image/jpeg"), null, ContentTier.EXCLUSIVE).id())
                .collect(java.util.stream.Collectors.toList());
    }

    private void publishVideo(User user) {
        mediaService.upload(reload(user), MediaType.VIDEO, file("v.mp4", "video/mp4"), null, ContentTier.EXCLUSIVE);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private MockMultipartFile file(String name, String contentType) {
        return new MockMultipartFile("file", name, contentType, new byte[]{1, 2, 3, 4});
    }
}
