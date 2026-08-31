package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
import com.nightgals.billing.CreatorPackageCode;
import com.nightgals.common.ApiException;
import com.nightgals.discovery.FeedService;
import com.nightgals.media.ContentTier;
import com.nightgals.media.MediaService;
import com.nightgals.media.MediaType;
import com.nightgals.profile.Gender;
import com.nightgals.profile.ProfileService;
import com.nightgals.profile.dto.ProfileRequest;
import com.nightgals.reels.ReelService;
import com.nightgals.user.AccountType;
import com.nightgals.user.Role;
import com.nightgals.user.User;
import com.nightgals.user.UserRepository;
import com.nightgals.user.VerificationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A creator is on the site while she is paying for it, and not otherwise.
 *
 * <p>Publishing and being published were two different gates and only the first
 * existed: a creator whose trial ran out could post nothing new, but everything
 * she had already posted stayed up for good. The package bought the right to add
 * to a shop window that stayed open whether she paid or not.
 *
 * <p>So most of this asserts absence, and the last test asserts that buying
 * brings all of it back — nothing is deleted, and a creator who pays after a
 * lapse finds her profile exactly as she left it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "nightgals.monetization.enabled=true",
        "nightgals.monetization.free-trial=P21D",
        "nightgals.creator-packages.enabled=true",
})
@Transactional
class UnpaidCreatorHiddenTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired MediaService mediaService;
    @Autowired ReelService reelService;
    @Autowired BillingService billingService;
    @Autowired FeedService feedService;
    @Autowired UserRepository userRepository;
    @PersistenceContext EntityManager entityManager;

    @Test
    @DisplayName("While the trial runs she is on the site")
    void theTrialKeepsHerVisible() {
        User creator = creatorWithWork();

        assertThat(creator.isOnTrial()).isTrue();
        assertThat(feedIds()).contains(creator.getId());
        assertThat(mediaService.listPublic(creator.getId(), null)).isNotEmpty();
    }

    @Test
    @DisplayName("When it runs out and she has bought nothing, she leaves every listing")
    void anExpiredTrialTakesHerOff() {
        User creator = creatorWithWork();
        expireTrial(creator);

        assertThat(feedIds()).doesNotContain(creator.getId());
        assertThat(reelService.live()).noneSatisfy(
                r -> assertThat(r.creatorId()).isEqualTo(creator.getId()));
    }

    @Test
    @DisplayName("Her reel file stops answering on its own URL too")
    void theReelFileClosesAsWell() {
        User creator = creatorWithWork();
        UUID reelId = reelService.mine(creator.getId()).getFirst().id();

        // Reachable while she is on the site...
        assertThat(reelService.download(reelId)).isNotNull();

        expireTrial(creator);

        // ...and gone once she is not. A reel is free to watch, so filtering it
        // out of the strip alone would leave the file open to anybody with the
        // link — off the listing is not off the site.
        assertThatThrownBy(() -> reelService.download(reelId))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("Her profile and gallery stop answering on their own URLs too")
    void theDirectUrlsCloseAsWell() {
        User creator = creatorWithWork();
        User visitor = viewer();
        expireTrial(creator);

        // Not merely unlisted. A profile that still answered would leave the whole
        // thing one shared link wide.
        assertThatThrownBy(() -> profileService.getPublic(creator.getId(), reload(visitor)))
                .isInstanceOf(ApiException.class);
        assertThat(mediaService.listPublic(creator.getId(), reload(visitor))).isEmpty();
        assertThat(mediaService.listPublic(creator.getId(), null)).isEmpty();
    }

    @Test
    @DisplayName("She can still see her own work, and so can staff")
    void sheIsNeverHiddenFromHerself() {
        User creator = creatorWithWork();
        expireTrial(creator);

        // The whole point of the paywall is that she buys the package to bring it
        // back. A creator who cannot see what she has lost cannot value that.
        assertThat(mediaService.listPublic(creator.getId(), reload(creator))).isNotEmpty();
        assertThat(profileService.getPublic(creator.getId(), reload(creator))).isNotNull();
        assertThat(mediaService.listPublic(creator.getId(), admin())).isNotEmpty();
    }

    @Test
    @DisplayName("Somebody who already paid keeps what they paid for")
    void aBuyerDoesNotLoseWhatTheyBought() {
        User creator = creatorWithWork();
        UUID item = mediaService.upload(reload(creator), MediaType.VIDEO,
                file("v.mp4", "video/mp4"), null, ContentTier.EXCLUSIVE, 5_000L).id();

        User buyer = viewer();
        // Off her own trial first, or the platform refuses the sale with "you
        // already have this" — a trial viewer can see everything, so there is
        // nothing to sell her.
        expireTrial(buyer);
        var checkout = billingService.unlockMedia(reload(buyer), item);
        billingService.settle(checkout.purchase().id(), null);

        expireTrial(creator);

        // She is off the site — but money changed hands for that clip, and taking
        // it away while keeping the payment is not a paywall.
        assertThat(feedIds()).doesNotContain(creator.getId());
        assertThat(mediaService.listPublic(creator.getId(), reload(buyer))).isNotEmpty();
        assertThat(profileService.getPublic(creator.getId(), reload(buyer))).isNotNull();
        assertThat(mediaService.download(item, reload(buyer))).isNotNull();
    }

    @Test
    @DisplayName("Somebody who never paid still sees nothing")
    void aNonBuyerStillSeesNothing() {
        User creator = creatorWithWork();
        User stranger = viewer();
        expireTrial(creator);

        // The exemption is for buyers, not for anybody who happens to be signed in.
        assertThat(mediaService.listPublic(creator.getId(), reload(stranger))).isEmpty();
        assertThatThrownBy(() -> profileService.getPublic(creator.getId(), reload(stranger)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("Buying a package puts everything back")
    void payingRestoresAllOfIt() {
        User creator = creatorWithWork();
        User visitor = viewer();
        expireTrial(creator);
        assertThat(feedIds()).doesNotContain(creator.getId());

        var checkout = billingService.buyCreatorPackage(reload(creator), CreatorPackageCode.PRO.name());
        billingService.settle(checkout.purchase().id(), null);
        backdateStarts();

        assertThat(feedIds()).contains(creator.getId());
        assertThat(mediaService.listPublic(creator.getId(), reload(visitor))).isNotEmpty();
        assertThat(profileService.getPublic(creator.getId(), reload(visitor))).isNotNull();
    }

    // ------------------------------------------------------------- helpers

    private List<UUID> feedIds() {
        // The feed is native SQL, which Hibernate does not auto-flush for.
        entityManager.flush();
        return feedService.feed(null, null, null, null, null, null, null, null, null,
                        PageRequest.of(0, 100)).content().stream()
                .map(com.nightgals.discovery.dto.MemberCardResponse::userId)
                .toList();
    }

    /**
     * Moves every package's start back an hour.
     *
     * <p>The visibility predicate asks Postgres for {@code NOW()}, which inside
     * this class's transaction is the instant it began — before every row the test
     * has written since. A package bought a millisecond ago has therefore not
     * started yet as far as the query is concerned. Real requests never see it:
     * each is its own transaction.
     */
    private void backdateStarts() {
        entityManager.flush();
        entityManager.createNativeQuery(
                "UPDATE creator_packages SET starts_at = starts_at - INTERVAL '1 hour'").executeUpdate();
        entityManager.clear();
    }

    private void expireTrial(User creator) {
        User managed = reload(creator);
        managed.setTrialEndsAt(Instant.now().minus(1, ChronoUnit.DAYS));
        userRepository.saveAndFlush(managed);
    }

    private User creatorWithWork() {
        User creator = register(AccountType.CREATOR);
        profileService.createOrUpdate(creator, new ProfileRequest(
                null, "Weekend only", LocalDate.of(1996, 5, 5),
                Gender.FEMALE, "Douala", "Cameroon", null, null));
        User managed = reload(creator);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        User saved = userRepository.saveAndFlush(managed);

        mediaService.upload(saved, MediaType.PHOTO, file("p.jpg", "image/jpeg"),
                null, ContentTier.FREE, null);
        reelService.post(reload(saved), file("r.mp4", "video/mp4"), "tonight");
        return reload(saved);
    }

    private User admin() {
        User staff = register(AccountType.VIEWER);
        staff.setRole(Role.ADMIN);
        return userRepository.saveAndFlush(staff);
    }

    private User viewer() {
        return register(AccountType.VIEWER);
    }

    private User register(AccountType type) {
        String email = "unpaid-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", type, null), null);
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private MultipartFile file(String name, String type) {
        return new MockMultipartFile("file", name, type, new byte[] {1, 2, 3});
    }
}
