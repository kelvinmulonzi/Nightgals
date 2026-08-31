package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
import com.nightgals.billing.CreatorPackageCode;
import com.nightgals.billing.CreatorPackageService;
import com.nightgals.common.ApiException;
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
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pro, Diamond and Black Diamond.
 *
 * <p>Unlike the bronze/silver/gold set they replaced, every package covers photos
 * <em>and</em> video. What differs is how many premium videos may be posted, how
 * many minutes of live per day, and where she ranks in search.
 *
 * <p>Packages are off in the shared test profile so the older media tests keep
 * testing media rules; this class turns them on for itself, and turns the free
 * trial off - otherwise every creator here would be covered by that instead.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "nightgals.creator-packages.enabled=true",
        "nightgals.monetization.free-trial=",
})
@Transactional
class CreatorPackageTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired MediaService mediaService;
    @Autowired BillingService billingService;
    @Autowired CreatorPackageService packageService;
    @Autowired UserRepository userRepository;
    @Autowired com.nightgals.discovery.FeedService feedService;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;

    @Test
    @DisplayName("A verified creator with no package and no trial cannot publish")
    void noPackageNoPublishing() {
        User creator = approvedCreator();

        assertThatThrownBy(() -> publishVideo(creator, ContentTier.EXCLUSIVE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Choose a package");
    }

    @Test
    @DisplayName("Every package covers photos and video both")
    void allPackagesCoverBoth() {
        for (CreatorPackageCode code : CreatorPackageCode.values()) {
            User creator = approvedCreator();
            buy(creator, code);

            publishPhoto(creator);
            publishVideo(creator, ContentTier.EXCLUSIVE);

            var status = packageService.status(reload(creator));
            assertThat(status.code()).isEqualTo(code);
            assertThat(status.canPostPhotos()).as("%s photos", code).isTrue();
            assertThat(status.canPostVideos()).as("%s videos", code).isTrue();
            assertThat(status.canGoLive()).as("%s live", code).isTrue();
        }
    }

    @Test
    @DisplayName("The premium video allowance is what separates the tiers")
    void videoAllowanceIsEnforced() {
        User creator = approvedCreator();
        buy(creator, CreatorPackageCode.PRO);

        // Pro is 2 premium videos in the test configuration.
        publishVideo(creator, ContentTier.EXCLUSIVE);
        publishVideo(creator, ContentTier.EXCLUSIVE);

        assertThatThrownBy(() -> publishVideo(creator, ContentTier.EXCLUSIVE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Delete one, or upgrade");

        assertThat(packageService.status(reload(creator)).videosRemaining()).isZero();
    }

    @Test
    @DisplayName("Free videos are the shop window and are not metered")
    void freeVideosAreNotMetered() {
        User creator = approvedCreator();
        buy(creator, CreatorPackageCode.PRO);

        // Well past the 2-video premium allowance.
        for (int i = 0; i < 5; i++) {
            publishVideo(creator, ContentTier.FREE);
        }

        // The premium allowance is untouched by any of them.
        assertThat(packageService.status(reload(creator)).videosUsed()).isZero();
        publishVideo(creator, ContentTier.EXCLUSIVE);
    }

    @Test
    @DisplayName("Deleting frees a slot - the limit is on what is posted, not what was ever posted")
    void deletingFreesASlot() {
        User creator = approvedCreator();
        buy(creator, CreatorPackageCode.PRO);

        UUID first = publishVideo(creator, ContentTier.EXCLUSIVE);
        publishVideo(creator, ContentTier.EXCLUSIVE);
        assertThat(packageService.status(reload(creator)).videosRemaining()).isZero();

        mediaService.delete(reload(creator), first);

        assertThat(packageService.status(reload(creator)).videosRemaining()).isEqualTo(1);
        publishVideo(creator, ContentTier.EXCLUSIVE);
    }

    @Test
    @DisplayName("Upgrading applies at once and brings the bigger allowance with it")
    void upgradeAppliesImmediately() {
        User creator = approvedCreator();
        buy(creator, CreatorPackageCode.PRO);
        publishVideo(creator, ContentTier.EXCLUSIVE);
        publishVideo(creator, ContentTier.EXCLUSIVE);

        buy(creator, CreatorPackageCode.BLACK_DIAMOND);

        var status = packageService.status(reload(creator));
        assertThat(status.code()).isEqualTo(CreatorPackageCode.BLACK_DIAMOND);
        assertThat(status.videoLimit()).isEqualTo(10);
        assertThat(status.liveMinutesPerDay()).isEqualTo(120);
        publishVideo(creator, ContentTier.EXCLUSIVE);
    }

    @Test
    @DisplayName("Renewing the same package extends it rather than starting over")
    void renewalExtends() {
        User creator = approvedCreator();
        buy(creator, CreatorPackageCode.DIAMOND);
        var first = packageService.activeFor(creator.getId()).orElseThrow().getExpiresAt();

        buy(creator, CreatorPackageCode.DIAMOND);
        var second = packageService.activeFor(creator.getId()).orElseThrow().getExpiresAt();

        // A week on top of what was left, not a week from today.
        assertThat(second).isAfter(first);
    }

    @Test
    @DisplayName("Nothing publishes on an unsettled purchase")
    void pendingPurchaseGrantsNothing() {
        User creator = approvedCreator();
        billingService.buyCreatorPackage(reload(creator), "BLACK_DIAMOND");

        assertThat(packageService.status(reload(creator)).active()).isFalse();
        assertThatThrownBy(() -> publishVideo(creator, ContentTier.EXCLUSIVE))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Choose a package");
    }

    @Test
    @DisplayName("Discover filters to one named tier, not to \"has any package\"")
    void feedFiltersByTier() {
        User pro = approvedCreator();
        User black = approvedCreator();
        buy(pro, CreatorPackageCode.PRO);
        buy(black, CreatorPackageCode.BLACK_DIAMOND);
        backdateStarts();

        // No tier asked for is everybody. Asserted first, so a feed that returns
        // nothing at all fails here rather than looking like a broken predicate.
        assertThat(idsIn(feed(null, null))).contains(pro.getId(), black.getId());

        // The whole reason this replaced `premiumOnly`: that flag meant "holds a
        // package", so asking for Black Diamond handed back the Pro creator too.
        assertThat(idsIn(feed("BLACK_DIAMOND", null)))
                .contains(black.getId())
                .doesNotContain(pro.getId());

        assertThat(idsIn(feed("PRO", null)))
                .contains(pro.getId())
                .doesNotContain(black.getId());
    }

    @Test
    @DisplayName("The verified filter reads the checked document, not the publishing gate")
    void feedFiltersByVerified() {
        User plain = approvedCreator();
        User checked = approvedCreator();
        User managed = reload(checked);
        managed.setIdentityVerifiedAt(java.time.Instant.now());
        userRepository.saveAndFlush(managed);

        // Both need a package to be on the site at all: this class runs with the
        // trial switched off, and a creator who is neither on a trial nor paying
        // is not in the feed for any filter to act on. Without these two lines
        // the assertion below passes or fails for the wrong reason.
        buy(plain, CreatorPackageCode.PRO);
        buy(checked, CreatorPackageCode.PRO);
        backdateStarts();

        // Both are APPROVED - that is what lets them publish, and with identity
        // checks switched off it is granted automatically. Only one of them has
        // had a document looked at by a person, and that is what the badge and
        // this filter both mean.
        assertThat(idsIn(feed(null, true)))
                .contains(checked.getId())
                .doesNotContain(plain.getId());
    }

    /**
     * Moves every package's start back an hour.
     *
     * <p>Not cosmetic. The feed's package predicate is native SQL and asks
     * Postgres for {@code NOW()}, which is the <em>transaction's</em> start time
     * - and this class is {@code @Transactional}, so that instant predates every
     * row the test then writes. A package bought a millisecond ago therefore has
     * not started yet as far as the query is concerned, and the filter matches
     * nobody however correct it is.
     *
     * <p>Real requests never see this: each one is its own transaction, and the
     * package was bought in an earlier one.
     */
    private void backdateStarts() {
        entityManager.flush();
        entityManager.createNativeQuery(
                        "UPDATE creator_packages SET starts_at = starts_at - INTERVAL '1 hour'")
                .executeUpdate();
        entityManager.clear();
    }

    private java.util.List<com.nightgals.discovery.dto.MemberCardResponse> feed(
            String tier, Boolean verifiedOnly) {
        // The feed is a native query, and Hibernate does not auto-flush for those
        // the way it does for JPQL that touches the same tables. Inside this
        // class's transaction the profiles and packages just written are still
        // sitting in the persistence context, so without this the feed reads an
        // empty database and every assertion below passes for the wrong reason.
        userRepository.flush();
        return feedService.feed(null, null, null, null, null, null, null,
                tier, verifiedOnly, org.springframework.data.domain.PageRequest.of(0, 100)).content();
    }

    private java.util.List<java.util.UUID> idsIn(
            java.util.List<com.nightgals.discovery.dto.MemberCardResponse> cards) {
        return cards.stream().map(com.nightgals.discovery.dto.MemberCardResponse::userId).toList();
    }

    @Test
    @DisplayName("Search priority rises with the tier")
    void searchPriorityRisesWithTier() {
        User pro = approvedCreator();
        User diamond = approvedCreator();
        User black = approvedCreator();
        buy(pro, CreatorPackageCode.PRO);
        buy(diamond, CreatorPackageCode.DIAMOND);
        buy(black, CreatorPackageCode.BLACK_DIAMOND);

        assertThat(packageService.searchPriorityOf(reload(black)))
                .isGreaterThan(packageService.searchPriorityOf(reload(diamond)));
        assertThat(packageService.searchPriorityOf(reload(diamond)))
                .isGreaterThan(packageService.searchPriorityOf(reload(pro)));
        // Somebody with nothing ranks below all of them.
        assertThat(packageService.searchPriorityOf(approvedCreator())).isZero();
    }

    @Test
    @DisplayName("The daily live allowance comes from the package")
    void liveAllowanceComesFromThePackage() {
        User creator = approvedCreator();
        assertThat(packageService.dailyLiveMinutesFor(creator)).isZero();

        buy(creator, CreatorPackageCode.PRO);
        assertThat(packageService.dailyLiveMinutesFor(reload(creator))).isEqualTo(15);

        buy(creator, CreatorPackageCode.DIAMOND);
        assertThat(packageService.dailyLiveMinutesFor(reload(creator))).isEqualTo(45);
    }

    @Test
    @DisplayName("An unknown package code is refused")
    void unknownPackageRefused() {
        assertThatThrownBy(() -> billingService.buyCreatorPackage(approvedCreator(), "PLATINUM"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("PRO, DIAMOND, BLACK_DIAMOND");
    }

    @Test
    @DisplayName("The catalogue is the three packages, cheapest first")
    void catalogueIsOrdered() {
        var catalogue = packageService.catalogue();

        assertThat(catalogue).extracting(p -> p.code())
                .containsExactly(CreatorPackageCode.PRO, CreatorPackageCode.DIAMOND,
                        CreatorPackageCode.BLACK_DIAMOND);
        assertThat(catalogue.getFirst().priceMinor()).isEqualTo(5_000L);
        assertThat(catalogue.getLast().priceMinor()).isEqualTo(15_000L);
        // XAF has no minor unit, so the display is the price itself.
        assertThat(catalogue.getLast().priceDisplay()).isEqualTo("15000");
        assertThat(catalogue.getLast().liveAllowanceLabel()).isEqualTo("2 hours per day");
    }

    // ------------------------------------------------------------- helpers

    private User buy(User creator, CreatorPackageCode code) {
        var checkout = billingService.buyCreatorPackage(reload(creator), code.name());
        billingService.settle(checkout.purchase().id(), null);
        return reload(creator);
    }

    private UUID publishPhoto(User creator) {
        return mediaService.upload(reload(creator), MediaType.PHOTO, photo(),
                null, ContentTier.EXCLUSIVE, null).id();
    }

    private UUID publishVideo(User creator, ContentTier tier) {
        return mediaService.upload(reload(creator), MediaType.VIDEO, video(), null, tier, null).id();
    }

    private User approvedCreator() {
        String email = "creator-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();

        profileService.createOrUpdate(user, new ProfileRequest(
                null, "Here for the weekend", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Douala", "Cameroon", null, null));

        User managed = reload(user);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        return userRepository.saveAndFlush(managed);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private MultipartFile photo() {
        return new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private MultipartFile video() {
        return new MockMultipartFile("file", "v.mp4", "video/mp4", new byte[] {4, 5, 6});
    }
}
