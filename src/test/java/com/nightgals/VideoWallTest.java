package com.nightgals;

import com.nightgals.auth.AuthService;
import com.nightgals.auth.dto.RegisterRequest;
import com.nightgals.billing.BillingService;
import com.nightgals.discovery.FeedService;
import com.nightgals.discovery.dto.VideoCardResponse;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The video wall: everything there is to watch, in one place.
 *
 * <p>Discover answers "who is here". This answers "what is there to watch" - a
 * question the site could not answer at all, because a clip was reachable only
 * by finding the creator who posted it first.
 *
 * <p>The two surfaces must agree on who is visible. A clip on the wall whose
 * creator is hidden from Discover would be a leak, so the tests below check the
 * same exclusions from this side.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class VideoWallTest {

    @Autowired AuthService authService;
    @Autowired ProfileService profileService;
    @Autowired MediaService mediaService;
    @Autowired BillingService billingService;
    @Autowired FeedService feedService;
    @Autowired UserRepository userRepository;

    // ---------------------------------------------------------------- listing

    @Test
    @DisplayName("Videos from every creator land on one wall, newest first")
    void wallGathersEverybody() {
        User first = creator("Amina");
        User second = creator("Blaise");
        UUID older = video(first, ContentTier.FREE, "one");
        UUID newer = video(second, ContentTier.FREE, "two");

        List<VideoCardResponse> wall = wall(null, null);

        assertThat(ids(wall)).contains(older, newer);
        // Recency alone, not package rank: this is a wall of work, not a
        // listing of people.
        assertThat(wall.stream().map(VideoCardResponse::createdAt))
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("Photos are not on it")
    void photosStayOut() {
        User creator = creator("Amina");
        video(creator, ContentTier.FREE, null);

        // Her profile picture is a photo and the only other thing she has posted.
        assertThat(wall(null, null)).hasSize(countedElsewhere(creator));
    }

    @Test
    @DisplayName("Each clip carries the creator who posted it, and a face")
    void clipsCarryTheirCreator() {
        User creator = creator("Amina");
        UUID clip = video(creator, ContentTier.FREE, "night out");

        VideoCardResponse card = find(wall(null, null), clip);
        assertThat(card.username()).isEqualTo(creator.getUsername());
        assertThat(card.displayName()).isEqualTo("Amina");
        assertThat(card.caption()).isEqualTo("night out");
        // The profile picture she was forced to post, which is free and public.
        assertThat(card.creatorPhotoUrl()).isNotNull();
    }

    @Test
    @DisplayName("The badge travels with the clip, earned or not")
    void clipsCarryTheBadge() {
        User unverified = creator("Amina");
        UUID plain = video(unverified, ContentTier.FREE, null);
        // Nobody checked her documents, so the wall must not imply somebody did.
        assertThat(find(wall(null, null), plain).verified()).isFalse();

        User checked = creator("Blaise");
        User managed = reload(checked);
        managed.setIdentityVerifiedAt(java.time.Instant.now());
        userRepository.saveAndFlush(managed);
        UUID badged = video(managed, ContentTier.FREE, null);

        // The wall never loads her profile, so if the badge did not ride along
        // there is nowhere a viewer could have learnt it.
        assertThat(find(wall(null, null), badged).verified()).isTrue();
    }

    // ---------------------------------------------------------------- filtering

    @Test
    @DisplayName("The tier filter separates the shop window from what is for sale")
    void tierFilters() {
        User creator = creator("Amina");
        UUID free = video(creator, ContentTier.FREE, null);
        UUID exclusive = video(creator, ContentTier.EXCLUSIVE, null);

        assertThat(ids(wall(null, ContentTier.FREE))).contains(free).doesNotContain(exclusive);
        assertThat(ids(wall(null, ContentTier.EXCLUSIVE))).contains(exclusive).doesNotContain(free);
        assertThat(ids(wall(null, null))).contains(free, exclusive);
    }

    @Test
    @DisplayName("Filtering to exclusive lists what has not been paid for, rather than hiding it")
    void exclusiveFilterIsNotAnEntitlementFilter() {
        User creator = creator("Amina");
        UUID clip = video(creator, ContentTier.EXCLUSIVE, null);

        // A stranger asking for exclusive clips is shopping. Returning only the
        // ones she already owns would be an empty page for every new viewer.
        VideoCardResponse card = find(wall(viewer(), ContentTier.EXCLUSIVE), clip);
        assertThat(card.locked()).isTrue();
        assertThat(card.priceMinor()).isNotNull();
    }

    // ---------------------------------------------------------------- the paywall

    @Test
    @DisplayName("A locked clip comes back priced and with no URL")
    void lockedClipsArePricedNotServed() {
        User creator = creator("Amina");
        UUID clip = video(creator, ContentTier.EXCLUSIVE, null);

        VideoCardResponse card = find(wall(viewer(), null), clip);
        assertThat(card.locked()).isTrue();
        assertThat(card.url()).isNull();
        assertThat(card.priceMinor()).isNotNull();
        assertThat(card.priceDisplay()).isNotBlank();
        assertThat(card.currency()).isNotBlank();
    }

    @Test
    @DisplayName("Buying it turns the tile into something playable")
    void buyingUnlocksTheTile() {
        User creator = creator("Amina");
        UUID clip = video(creator, ContentTier.EXCLUSIVE, null);
        User viewer = viewer();

        var checkout = billingService.unlockMedia(viewer, clip);
        billingService.settle(checkout.purchase().id(), null);

        VideoCardResponse card = find(wall(reload(viewer), null), clip);
        assertThat(card.locked()).isFalse();
        assertThat(card.url()).isNotNull();
        // Still EXCLUSIVE - what it is has not changed, only who may watch it.
        assertThat(card.tier()).isEqualTo(ContentTier.EXCLUSIVE);
    }

    @Test
    @DisplayName("An anonymous visitor gets the free clips and a price on the rest")
    void anonymousSeesTheShopWindow() {
        User creator = creator("Amina");
        UUID free = video(creator, ContentTier.FREE, null);
        UUID exclusive = video(creator, ContentTier.EXCLUSIVE, null);

        List<VideoCardResponse> wall = wall(null, null);
        assertThat(find(wall, free).url()).isNotNull();
        assertThat(find(wall, exclusive).url()).isNull();
        assertThat(find(wall, exclusive).locked()).isTrue();
    }

    // ---------------------------------------------------------------- exclusions

    @Test
    @DisplayName("A creator hidden from Discover is hidden here too")
    void hiddenCreatorsStayOff() {
        User creator = creator("Amina");
        UUID clip = video(creator, ContentTier.FREE, null);
        assertThat(ids(wall(null, null))).contains(clip);

        profileService.createOrUpdate(reload(creator), new ProfileRequest(
                "Amina", null, LocalDate.of(1996, 5, 5), Gender.FEMALE,
                "Douala", "Cameroon", null, false, null));

        assertThat(ids(wall(null, null))).doesNotContain(clip);
    }

    @Test
    @DisplayName("A clip a moderator took down leaves the wall")
    void takenDownClipsLeave() {
        User creator = creator("Amina");
        UUID clip = video(creator, ContentTier.FREE, null);

        mediaService.takeDown(clip, "Someone else is in it");

        assertThat(ids(wall(null, null))).doesNotContain(clip);
    }

    // ---------------------------------------------------------------- helpers

    private List<VideoCardResponse> wall(User viewer, ContentTier tier) {
        return feedService.videos(viewer, tier, PageRequest.of(0, 100)).content();
    }

    private List<UUID> ids(List<VideoCardResponse> wall) {
        return wall.stream().map(VideoCardResponse::id).toList();
    }

    private VideoCardResponse find(List<VideoCardResponse> wall, UUID id) {
        return wall.stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("clip missing from the wall"));
    }

    /** How many clips the wall holds - which is every video and no photo. */
    private int countedElsewhere(User creator) {
        return (int) mediaService.listOwn(creator.getId()).stream()
                .filter(m -> m.type() == MediaType.VIDEO)
                .count();
    }

    private UUID video(User creator, ContentTier tier, String caption) {
        return mediaService.upload(reload(creator), MediaType.VIDEO, clip(), caption, tier, 3_000L).id();
    }

    private MockMultipartFile clip() {
        return new MockMultipartFile("file", "v.mp4", "video/mp4", new byte[] {1, 2, 3, 4});
    }

    private MockMultipartFile photo() {
        return new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[] {1, 2, 3, 4});
    }

    /** Verified, discoverable, and already holding the forced-free profile picture. */
    private User creator(String displayName) {
        String email = "creator-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "correct-horse-9", AccountType.CREATOR, null), null);
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();

        profileService.createOrUpdate(user, new ProfileRequest(
                displayName, null, LocalDate.of(1996, 5, 5), Gender.FEMALE,
                "Douala", "Cameroon", null, null, null));

        User managed = reload(user);
        managed.setVerificationStatus(VerificationStatus.APPROVED);
        userRepository.saveAndFlush(managed);

        mediaService.upload(reload(user), MediaType.PHOTO, photo(), null, ContentTier.FREE, null);
        return reload(user);
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
